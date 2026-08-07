# Database Benchmark App

A cross-paradigm benchmarking platform that runs the **same logical workload against many database engines in parallel** and reports latency at the driver level with nanosecond precision.

Three microservices cooperate: an LLM-driven schema/script generator, a Java 21 execution engine that owns containers, data generation and timing, and a React dashboard that streams telemetry live.

---

## What actually happens, step by step

A benchmark's lifecycle is deterministic — every step below always fires in the same order, and each has a clear owner.

### 1. User creates a benchmark (frontend → backend)

The user picks a **topic** (free-text idea, e.g. *"movie management system with actors, directors, reviews"*), a **depth** (longest chain of relationships), and a list of **database targets** (`type:name:version`). The frontend `NewBenchmarkPage` sends `POST /api/benchmarks` to the backend.

The backend persists a `Benchmark` row with status `PENDING` and one `BenchmarkDatabase` per target, then hands the job off to `BenchmarkDeploymentService`.

### 2. Backend asks script-creator for a schema + scripts

`ScriptGenerationPort` (implemented by `ScriptCreatorClient`) makes a single call to the script-creator's `POST /generate` endpoint, passing the topic, depth, and the list of targets.

Inside script-creator:

- **Phase A — Logical schema.** A LangGraph agent loop generates a technology-independent schema (entities, attributes, data types, relationships, cardinalities). Deterministic validators check depth via graph traversal; LLM validators check topic coverage and completeness. The loop refines the schema until every validator passes or the iteration limit is hit.
- **Phase B — Per-target scripts (parallel).** For every requested target, an independent agent loop generates an initialization script (`.sql`, `.cypher`, `.js`, ...) that faithfully implements the schema for that engine. Each script goes through its own generate → validate → refine loop.

The response contains one `LogicalSchema` object plus one script per target, each with container metadata (image, port, env).

### 3. Backend spins up containers

For each target, `ContainerManagementPort` (Docker via `docker-java`) starts a fresh container with strict CPU/RAM caps (level playing field — see `BenchmarkDeploymentService`). Ports are auto-assigned; env vars come from script-creator's response.

Once the container is healthy, `ScriptExecutionPort` runs the initialization script (`psql`, `cypher-shell`, `mongosh`, native HTTP client, etc.). The database now has an **empty but fully-shaped schema**.

Status transitions surface via SSE: `PENDING → GENERATING_SCRIPTS → STARTING_CONTAINERS → INITIALIZING → RUNNING`.

### 4. User configures and runs benchmark operations

From `BenchmarkDetailPage` the user can trigger four kinds of runs:

- **Insert** — fill the schema with generated rows.
- **Read** — point lookups on real inserted IDs, optionally with 1-hop child expansion.
- **Delete** — remove real IDs, optionally cascading through child relationships.
- **Scenario** — cross-paradigm query workloads (traversal, aggregate, range filter, KNN vector search).

Every operation is executed against **all selected databases in parallel** by a `PerDbExecutor` backed by virtual threads (Project Loom). Each database gets its own virtual thread → no head-of-line blocking, no thread-pool tuning.

### 5. Real-time telemetry streams back

- Each driver emits `TimedOperation` records (see below).
- `BenchmarkEventPort` broadcasts SSE events on `/api/events/{benchmarkId}` — status changes, per-batch progress, per-DB completions, resource-usage snapshots (CPU %, RSS MB from `docker stats`).
- The frontend has one SSE hook per operation type (`useInsertRunEvents`, `useReadRunEvents`, etc.); it merges live events into React state so charts animate as the run proceeds.

### 6. Results persist; reports become queryable

After a run finishes, `BenchmarkRun` + `BenchmarkResult` rows are written (one result per DB). The comparison report (`BenchmarkComparisonPage`) reads them back and renders side-by-side tables, latency distributions, a paradigm radar chart, and resource metrics. Users can export raw runs to JSON/CSV or dump the entire benchmark as a `.zip` bundle for reproducible re-import.

---

## How we measure time

Timing precision is the whole point of this app — everything below is designed to remove sources of noise.

### Two clocks per operation

Every driver returns a `TimedOperation` record (`engine/timing/TimedOperation.java`) carrying **two independent measurements**:

| Field | What it means | How it's computed |
|-------|---------------|-------------------|
| `dbTimeNs` | Time the database engine spent doing work, as observed by the driver | Sum of per-batch `System.nanoTime()` deltas measured **immediately around** each driver call (`PreparedStatement.executeBatch()`, `session.run()`, `collection.insertMany()`, ...) |
| `wireTimeNs` | Total wall-clock of the whole operation as seen by the orchestrator | `System.nanoTime()` delta wrapping the outer loop over all entities/batches |
| `overheadNs` | `wireTimeNs - dbTimeNs` | Time spent generating rows, hashing IDs, walking cascade graph — everything that isn't the DB itself |

This split is **critical**. Ratio `dbTimeNs / wireTimeNs` tells you whether an engine is slow because *it* is slow, or because your ORM/serializer is slow. All UI charts split "DB time" from "wire time" from "overhead".

### One clock, no engine-native metrics

We deliberately do **not** use engine-native profiling (`EXPLAIN ANALYZE`, `db.serverStatus()`, `PROFILE`, etc.). Every driver measures `dbTimeNs` as a `System.nanoTime()` delta around the exact same call boundary — the network round-trip plus server-side execution. This makes cross-engine numbers directly comparable.

`System.nanoTime()` is monotonic and independent of wall-clock adjustments, so results are stable even under NTP drift.

### Per-batch samples → percentiles

`BulkInsertLoop.run()` (`engine/driver/support/`) times **each batch individually**. Every `System.nanoTime()` delta lands in a `long[]` inside `InsertAccumulator` / `SampledAccumulator`. When the operation finishes, `LatencyStats.from(samples)` sorts the array in place and interpolates **p50 / p95 / p99 / mean** in nanoseconds. See `engine/timing/LatencyStats.java`.

For read/delete workloads, `PerTargetLoop.run()` treats each individual ID as one sample — you get thousands of latency measurements per run, one per lookup. That's why the "latency distribution" charts are meaningful even at N=1000.

### Cascade timing decomposition

For cascade deletes we track **per-entity timing** (`ScenarioTimings` in `engine/driver/support/`). If you delete 100 users and each user cascades to 5 orders and 20 events, the driver reports how many rows were touched per entity and how long each level took. This lets the UI attribute total time to "root work vs cascade work".

### What virtual threads buy us

`PerDbExecutor` and every intra-run parallel section (`ThreadPoolExecutor` replaced by `Executors.newVirtualThreadPerTaskExecutor()`) uses Loom virtual threads. Two consequences for timing:

1. **No pool starvation.** N databases each get their own virtual thread; blocking on JDBC or HTTP doesn't tie up an OS thread, so measurements don't get skewed by queue-wait times we didn't ask for.
2. **Determinism across DBs.** All targets start within microseconds of each other because thread creation is essentially free; there's no ramp-up curve.

### Resource metrics run out-of-band

CPU/RAM samples from `docker stats` are collected on a **separate scheduler** and stamped with their own `System.nanoTime()` timestamps relative to run start. They never touch the hot timing path — measuring them can't perturb `dbTimeNs`.

---

## How we generate data

Data generation is the hardest part of the project (called out explicitly in the project brief). Below is the exact pipeline.

### Source of truth: `LogicalSchema`

The script-creator produces a `LogicalSchema` — a technology-independent description of the world. The backend keeps this as the canonical model (`engine/schema/`); no engine-specific detail leaks back up.

- **Entities** — name + list of `LogicalAttribute`s. Attributes carry `LogicalDataType` (`UUID`, `STRING`, `TEXT`, `INTEGER`, `BIGINT`, `DECIMAL`, `BOOLEAN`, `DATE`, `TIMESTAMP`, `JSON`, `ENUM`, `VECTOR`), nullability, PK flag, precision/scale, enum values, vector dimensions.
- **Relationships** — `LogicalRelationship` with cardinality (`ONE_TO_ONE`, `ONE_TO_MANY`, `MANY_TO_MANY`) and a default `ratio` — average children per parent.

Every driver maps `LogicalDataType` to its engine-native type at insert time. There's no ambiguity: a `DECIMAL(10, 2)` in the schema is `NUMERIC(10, 2)` in Postgres, `Decimal128` in Mongo, and a `double` in Weaviate.

### Cascade planning: reverse propagation

When the user picks **leaf entities** to insert (e.g. *"insert 1000 reviews"*), `CascadePlanner` (`engine/cascade/`) walks the relationship graph **backwards from the leaves** to derive how many parents are needed.

Algorithm (`CascadePlanner.plan()`):

1. Seed a BFS queue with each leaf and its user-requested `recordCount`.
2. For each entity dequeued, find all its parents (`schema.relationshipsTargeting(child)`).
3. For each parent, compute `parentCount = ceil(childCount / ratio)` using the relationship's `defaultRatio` (or a user override).
4. Merge with `Math::max` — if the same parent is reached from two children, take the larger count.
5. When the graph is fully explored, **topologically sort** parents before children (Kahn's algorithm on the relationship DAG). Cycles throw immediately.
6. Return a `CascadePlan` — an ordered list of `CascadeNode(entityName, recordCount, incomingFromParents)`.

This guarantees that when we insert entity X, every parent it depends on already has PKs available. It also **minimizes** the number of rows generated: no wasted parent rows.

### Row generation: `RecordBuilder` + `FakerCatalog` + `PrimaryKeyVault`

Given a `CascadePlan`, `RecordBuilder.generateAll()` produces `Map<entityName, List<GeneratedRow>>` in insert order.

For each row of each entity:

- **PK.** A fresh `UUID.randomUUID()`. Appended to `PrimaryKeyVault` for that entity so children can reference it.
- **FK.** For every attribute whose column matches a parent's FK reference, pull a **random existing PK** from the vault via `PrimaryKeyVault.randomPk(parentEntity)`. Because the plan is topologically sorted, parents are always populated first.
- **Regular attribute.** Delegated to `FakerCatalog.generate(attr)`.

`FakerCatalog` uses **two lookup layers**:

1. **Name-based semantic matching** for text-like attributes (`STRING`, `TEXT`, `JSON`): `email` → `faker.internet().emailAddress()`, `phone` → phone number, `first_name` / `last_name` / `full_name` → human names, `nationality` → country, `city`, `address`, `slug`, `bio`/`description`/`synopsis` → paragraph, `title` → book title, and so on. This makes generated datasets look plausible instead of `"lorem ipsum sit amet"` everywhere.
2. **Type-based fallback** using data-faker's realistic generators — `LocalDate` up to 50 years back, `Instant` up to 1 year back, sensible bounds for numerics, Gaussian floats for `VECTOR` (default 128 dims, or explicit `vectorDimensions`), curated enum picks, `BigDecimal` with correct precision/scale.

Nullable non-PK columns are set to `null` with a **10% probability** (`NULL_PROBABILITY`) — this exercises engine handling of missing values in real workloads.

`PrimaryKeyVault` is thread-safe (`ConcurrentHashMap` of `CopyOnWriteArrayList`). Random FK selection uses `ThreadLocalRandom` so parallel entity generation doesn't contend on a single RNG.

### The same physical rows go to every database

A single `RecordBuilder.generateAll()` produces one `Map<String, List<GeneratedRow>>`. Each driver receives **the same map** and translates it to its native representation:

- **Relational** — `PreparedStatement` batched by `BatchSizes.forEngine()`, param-bound via `PgValueBinder` / `MysqlValueBinder`.
- **Document** — `Document`/`BsonDocument` via `DocBuilders`.
- **Graph** — parameterized Cypher with `Values.parameters(...)`.
- **Vector** — points/objects with the row's UUID as the vector ID, so cross-DB lookups by same ID still work.
- **Key-value** — key = `entityName:uuid`, value = JSON-serialized row.

Result: **every database contains the same rows with the same UUIDs**. Read and delete benchmarks can then be run with identical ID sets across all targets — a fair comparison of engine behavior, not of RNG state.

### The `EntityIdRegistry`

After insert, the backend persists `(benchmarkId, databaseId, entityName, logicalId → physicalId)` tuples in `EntityIdRegistry`. This lets read/delete benchmarks:

- Sample "N real IDs that exist in every DB" via `SELECT logical_id FROM entity_id_registry WHERE benchmark_id=? AND entity_name=? GROUP BY logical_id` — returns only IDs that are present across all targets.
- Map back to physical IDs per-target — if MongoDB's driver stored the row under a different key format, the registry knows the translation.

Read benchmarks with 1-hop children use `FrontierBfs` to walk the relationship graph one level per driver call, again with the exact same starting set on every DB.

---

## Repository layout

```
services/
  script-creator/     Python 3 + FastAPI + LangGraph + LiteLLM
                      Generates LogicalSchema and per-engine init scripts.
                      See services/script-creator/README.md.

  backend/            Java 21 + Spring Boot 3.5 + JPA + Loom + docker-java
                      Owns cascade planning, row generation, driver execution,
                      timing, container lifecycle, SSE stream.
                      See services/backend/README.md.

  frontend/           React 19 + Vite 8 + TypeScript 6 + Tailwind 4
                      Dashboard, live telemetry, comparison reports.
                      See services/frontend/README.md.
```

## Run locally

```bash
./start.sh
```

Ports: frontend `:5173`, backend `:8080`, script-creator `:8001`. The script kills anything already bound to those ports and streams logs to stdout.

Backend takes ~30–45 s to warm up on first boot (JPA schema init + Docker connection); the frontend has a `BackendReadyGate` that polls `/api/user` and shows a loader until the API answers.

## Supported paradigms

| Paradigm     | Engines                                        |
|--------------|------------------------------------------------|
| Relational   | PostgreSQL, MySQL                              |
| Graph        | Neo4j, ArangoDB, Memgraph                      |
| Document     | MongoDB, CouchDB, Elasticsearch                |
| Vector       | Qdrant, Weaviate                               |
| Key-value    | Redis, DynamoDB Local, etcd                    |
| Time-series  | TimescaleDB, InfluxDB, QuestDB                 |

Adding a new engine: implement one class extending `AbstractSqlDriver` (for SQL) or the driver interface directly, register it in `EngineDriverFactory`, and it plugs into every operation type at once.

## Design principles

- **Same logical workload, native execution paths.** Every engine gets to shine — Postgres uses `INSERT ... ON CONFLICT`, Mongo uses `insertMany` with `ordered=false`, Neo4j uses `UNWIND $rows CREATE (:X {...})`, Qdrant uses `PointStruct` batching. We never dumb down a fast engine to match a slow one.
- **Driver-level measurements.** No proxies, no eBPF, no sidecars. Times are captured inside the JVM, immediately around the driver call.
- **Fair containers.** Every DB gets the same CPU/RAM cap, the same startup wait, the same init sequence.
- **Deterministic where it matters.** Same UUIDs across DBs, same batch sizes per operation, same sampling strategy for percentiles.
- **Streaming, not polling.** SSE from the moment a run starts; the UI is a projection of the event stream, not a snapshot poller.
