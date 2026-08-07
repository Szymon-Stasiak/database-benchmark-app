# backend

Java 21 + Spring Boot 3.5 execution engine. Owns everything between "user clicks Run" and "results are persisted": container lifecycle, schema modeling, data generation, driver dispatch, timing, SSE streaming.

Everything below is oriented around the two hard problems this service solves: **measuring time honestly across paradigms** and **generating realistic, cross-comparable data at scale**.

---

## Runtime shape

- **Java 21** with virtual threads (Project Loom) everywhere concurrency happens.
- **Spring Boot 3.5** — MVC + JPA/Hibernate + SSE.
- **Local persistence** — SQLite via Hibernate (schema in `data/`). Keeps the project runnable without external services.
- **Docker orchestration** — `docker-java` for container start/stop/exec, CPU and memory caps applied per target.
- Boot with `./mvnw spring-boot:run` or via the repo-level `./start.sh`.

Layout (feature slices + hexagonal ports):

```
com.dbagnets.backend/
  BackendApplication          — entry point
  domain/                     — enums (DatabaseType, DatabaseEngine, DatabaseStatus)
  shared/                     — JPA entities, security, user, event port
  benchmark/
    setup/                    — create, deploy, cleanup benchmarks
    run/                      — insert/read/delete/scenario orchestrators
    result/                   — reports, dashboard
  engine/                     — the timing/data core; the interesting part
    schema/                   — LogicalSchema, LogicalEntity, LogicalAttribute, LogicalRelationship
    cascade/                  — CascadePlanner, CascadeNode, ForeignKeyResolver
    datagen/                  — RecordBuilder, FakerCatalog, PrimaryKeyVault
    driver/                   — per-engine drivers + shared support
      api/                    — driver interfaces (Insert/Read/Delete/Scenario contexts)
      support/                — timing loops, accumulators, connection caches
      sql/                    — AbstractSqlDriver + JDBC helpers
      engines/                — one package per engine (pg, mongo, neo4j, ...)
    timing/                   — TimedOperation, LatencyStats, RecordedId
    registry/                 — EntityIdRegistry (cross-DB ID sync)
    resource/                 — docker stats collector
    scenario/                 — Scenario definitions + result canonicalizer
  infrastructure/             — adapters for script-creator, docker, script exec
```

---

## Time measurement

### The `TimedOperation` record

Every driver call returns a `TimedOperation` (`engine/timing/TimedOperation.java`):

```java
public record TimedOperation(
        long dbTimeNs,              // sum of per-batch nanoTime deltas around the driver call
        long wireTimeNs,            // nanoTime delta wrapping the entire outer loop
        long rowsAffected,          // rows the DB acknowledged
        int conflictsSkipped,       // rows dropped by ON CONFLICT / duplicate-key detection
        List<RecordedId> recordedIds,      // (entityName, logicalId, physicalId) tuples
        long[] sampleDbTimeNs,             // one entry per batch or per unit-of-work
        Map<String, List<String>> cascadeDeletedByEntity  // per-entity cascade fanout
) { ... }
```

Two clocks, always:

- `dbTimeNs` — **only** the time spent in the driver call. Every measurement is a `System.nanoTime()` delta wrapped tightly around a single network round-trip (`PreparedStatement.executeBatch()`, `session.run()`, `collection.insertMany()`, HTTP `POST`, ...).
- `wireTimeNs` — total wall-clock from when the outer loop started until it finished. Row generation, cascade walking, ID hashing, and result collection all count here.
- `overheadNs()` — convenience derived accessor: `max(0, wireTimeNs - dbTimeNs)`.

The UI reports this split so users can distinguish "the engine is slow" from "our framework is slow".

### Two central loops

**`InsertOuterLoop`** — wraps the whole insert operation.

```java
long wireStart = System.nanoTime();
for (CascadeNode node : ctx.plan().nodesInInsertOrder()) {
    EntityOutcome outcome = handler.handle(node, rows);
    acc.accept(outcome);
}
return acc.finish(System.nanoTime() - wireStart);
```

**`BulkInsertLoop`** — per-entity batching + per-batch timing.

```java
long start = System.nanoTime();
long rowsAffected = handler.send(slice, batchIndex, totalBatches);
outcome.dbTimeNs += System.nanoTime() - start;
```

Every batch is sampled independently. `InsertAccumulator` merges per-batch measurements into a single `TimedOperation` for the entity, then all entities are folded into one `TimedOperation` for the whole insert run.

**`PerTargetLoop`** — for read/delete workloads, treats each individual ID as one sample.

```java
long[] samples = new long[targets.size()];
for (int i = 0; i < targets.size(); i++) {
    long start = System.nanoTime();
    long rows = handler.execute(entry);
    acc.sample(i, System.nanoTime() - start, rows);
}
```

A read benchmark with sampleSize=1000 produces 1000 latency samples per database. Enough for meaningful percentiles even on the tail.

### Percentiles

`LatencyStats.from(long[] samples)` sorts the array and interpolates:

```java
public static LatencyStats from(long[] samples) {
    long[] sorted = samples.clone();
    Arrays.sort(sorted);
    ...
    return new LatencyStats(
            percentile(sorted, 50),
            percentile(sorted, 95),
            percentile(sorted, 99),
            mean, sorted.length);
}
```

`p50 / p95 / p99` use linear interpolation between the two nearest sorted samples — see `LatencyStats.percentile()`. All values are in **nanoseconds**; the UI converts to milliseconds for display but everything upstream stays in the native `long`.

### Why not native engine profiling

Every engine has its own profiling story: `EXPLAIN ANALYZE`, `db.serverStatus()`, `PROFILE`, `slowlog`, `stats.durationMs`. None of them agree on where "the request" begins and ends. Using them would make cross-engine comparison impossible.

Instead, every driver measures the same thing: the delta between "I'm about to call the driver" and "the driver returned control to me". Same clock (`System.nanoTime()`), same call boundary, same JVM.

### Why virtual threads matter for timing

`PerDbExecutor` uses `Executors.newVirtualThreadPerTaskExecutor()`. Each database gets its own carrier-free virtual thread. Effects:

1. **All databases start within microseconds of each other** — no thread-pool warm-up, no queue-wait latency masquerading as DB latency.
2. **Blocking I/O doesn't block a platform thread** — the JVM parks the virtual thread and schedules another. Measurements aren't polluted by pool contention.
3. **We can fan out to hundreds of connections** without tuning pool sizes.

### Resource metrics are out-of-band

CPU/RAM samples from `docker stats` run on a **separate** scheduled executor (`engine/resource/`). They emit `ContainerStatsEvent`s over SSE with their own `System.nanoTime()`-based timestamps. They never share a thread with the hot timing path — sampling can't perturb `dbTimeNs`.

---

## Data generation

### `LogicalSchema` — the technology-independent model

The schema is engine-neutral. See `engine/schema/`.

```java
LogicalSchema
├── List<LogicalEntity>
│   └── List<LogicalAttribute>
│         ├── LogicalDataType  (UUID, STRING, TEXT, INTEGER, BIGINT, DECIMAL,
│         │                     BOOLEAN, DATE, TIMESTAMP, JSON, ENUM, VECTOR)
│         ├── isPrimaryKey
│         ├── isNullable
│         ├── precision, scale  (for DECIMAL)
│         ├── enumValues         (for ENUM)
│         └── vectorDimensions  (for VECTOR)
└── List<LogicalRelationship>
      ├── parentEntity, childEntity
      ├── Cardinality (ONE_TO_ONE, ONE_TO_MANY, MANY_TO_MANY)
      └── defaultRatio  (avg children per parent)
```

Each driver defines the mapping from `LogicalDataType` to its native type — `DECIMAL(10, 2)` becomes `NUMERIC(10, 2)` in Postgres, `Decimal128` in Mongo, a `double` in Weaviate. There is no ambiguity and no per-engine schema drift.

### `CascadePlanner` — reverse-BFS on relationship graph

`engine/cascade/CascadePlanner.java`.

Input: user picks leaf entities and record counts, e.g. *"insert 1000 reviews"*.
Output: `CascadePlan` — an ordered list of `CascadeNode(entityName, recordCount, incomingEdges)` in insert-safe order.

Algorithm:

1. Seed a queue with the leaves and their requested counts.
2. Pop `child`, find its parents via `schema.relationshipsTargeting(child)`.
3. For each `parent`, compute `parentCount = ceil(childCount / ratio)` where `ratio` comes from the relationship's `defaultRatio` (or a user override from `ratioOverrides`).
4. `derived.merge(parent, parentCount, Math::max)` — if the same parent is reached from two children, take the larger required count.
5. Enqueue the parent.
6. When exhausted, **topologically sort** all derived entities using Kahn's algorithm on the relationship DAG. Many-to-many edges are ignored for ordering — they don't create a hard dependency.
7. Cycles throw `IllegalStateException("Cycle detected in entity relationships; cascade planning is undefined")`.

Result: parents always come before children in `plan.nodesInInsertOrder()`. Data generation and insertion can walk the list top-to-bottom without ever needing an unresolved FK.

### `RecordBuilder` — deterministic row shape, plausible values

`engine/datagen/RecordBuilder.java`. Produces `Map<entityName, List<GeneratedRow>>` for the whole plan.

For each attribute:

- **Primary key** — `UUID.randomUUID().toString()`. Also appended to `PrimaryKeyVault` for that entity so children can reference it.
- **Foreign key** — column name matches a parent's FK slot: pull a random existing PK via `PrimaryKeyVault.randomPk(parentEntity)`. Because the plan is topologically sorted, parents are already populated.
- **Everything else** — delegated to `FakerCatalog.generate(attr)`.

Rows are generated **once per benchmark run** and sent to every driver. This is what makes cross-DB comparison fair: MongoDB and Neo4j receive the exact same UUIDs and the exact same attribute values.

### `FakerCatalog` — semantic then typed fallback

`engine/datagen/FakerCatalog.java`. Two-layer generator:

**Layer 1 — semantic name matching** (for `STRING` / `TEXT` / `JSON`):

| Column name contains  | Generated value                                              |
|-----------------------|--------------------------------------------------------------|
| `email`               | `faker.internet().emailAddress()`                            |
| `phone`               | `faker.phoneNumber().phoneNumber()`                          |
| `full_name`, `name`   | `faker.name().fullName()`                                    |
| `first_name`          | `faker.name().firstName()`                                   |
| `last_name`           | `faker.name().lastName()`                                    |
| `username`            | lowercase first name + `nextInt(1000, 9999)`                 |
| `password`            | 16-char hex from a fresh UUID                                |
| `url`, `uri`          | `faker.internet().url()`                                     |
| `avatar`, `image`     | `faker.internet().image()`                                   |
| `title`               | `faker.book().title()`                                       |
| `bio`, `description`, `synopsis`, `body`, `biography` | `faker.lorem().paragraph()` |
| `nationality`, `country` | `faker.country().name()`                                  |
| `language`            | `faker.nation().language()`                                  |
| `city`                | `faker.address().city()`                                     |
| `address`             | `faker.address().fullAddress()`                              |
| `slug`                | `faker.internet().slug()`                                    |
| `tags`                | JSON array of two lorem words                                |

**Layer 2 — type-based fallback**:

| `LogicalDataType` | Value                                                              |
|-------------------|--------------------------------------------------------------------|
| `UUID`            | `UUID.randomUUID().toString()`                                      |
| `STRING`          | `faker.lorem().sentence(3)`                                         |
| `TEXT`            | `faker.lorem().paragraph()`                                         |
| `INTEGER`         | `ThreadLocalRandom.nextInt(0, 100_000)`                             |
| `BIGINT`          | `ThreadLocalRandom.nextLong(0, 10_000_000_000L)`                    |
| `FLOAT`, `DOUBLE` | `ThreadLocalRandom.nextDouble(0.0, 1000.0)`                         |
| `DECIMAL`         | `BigDecimal` respecting `precision` and `scale`, HALF_UP rounding   |
| `BOOLEAN`         | `ThreadLocalRandom.nextBoolean()`                                   |
| `DATE`            | `LocalDate` up to 50 years in the past                              |
| `TIMESTAMP`       | `Instant` up to 1 year in the past                                  |
| `JSON`            | `{"key":"<lorem>","n":<int>}`                                       |
| `ENUM`            | random pick from `attr.enumValues()`                                |
| `VECTOR`          | `float[]` of `vectorDimensions` (default 128), Gaussian components  |

**Nullable non-PK columns are set to null with 10% probability** (`NULL_PROBABILITY = 0.10`). Real workloads have missing data; benchmark workloads should too.

Everything uses `ThreadLocalRandom` so parallel generation doesn't contend on a shared PRNG.

### `PrimaryKeyVault` — thread-safe PK registry

`engine/datagen/PrimaryKeyVault.java`. Thread-safe because entities can be generated in parallel (though currently they're generated sequentially per plan-order to keep the fair-comparison guarantee):

```java
private final ConcurrentHashMap<String, CopyOnWriteArrayList<String>> pksByEntity;

public void append(String entityName, String pk) { ... }
public String randomPk(String entityName) {         // uniform sample
    return pks.get(ThreadLocalRandom.current().nextInt(pks.size()));
}
```

If a child tries to reference a parent that hasn't been generated yet, `randomPk()` throws with a message pointing at the cascade planner — a bug there is the only way this can happen.

### `EntityIdRegistry` — cross-DB ID synchronization

After every insert, the backend persists `(benchmarkId, databaseId, entityName, logicalId → physicalId)` tuples (`engine/registry/`). Read and delete benchmarks then query:

```sql
SELECT logical_id
  FROM entity_id_registry
 WHERE benchmark_id = ?
   AND entity_name = ?
 GROUP BY logical_id
```

This returns only IDs that exist across **every** database in the benchmark. Read/delete workloads run against the exact same ID set on every target — the comparison is engine performance, not RNG luck.

For engines where the physical ID differs from the logical UUID (e.g. Mongo's `_id` variants, autoincrement PKs when we support them), the registry maps back to the driver-native form so lookups still work.

### Driver-side mapping — same rows, native representation

Every driver receives the same `Map<entityName, List<GeneratedRow>>` and translates it to its native form:

- **Relational** — `PreparedStatement` with `PgValueBinder` / `MysqlValueBinder`, batched by `BatchSizes.forEngine()`.
- **Document (Mongo, CouchDB)** — `Document`/`BsonDocument` via `DocBuilders`, `insertMany(ordered=false)`.
- **Graph (Neo4j, Memgraph)** — `UNWIND $rows CREATE (:X {...})` with parameterized rows via `Values.parameters()`.
- **Graph (Arango)** — batched HTTP `POST /_api/document/{collection}`.
- **Vector (Qdrant, Weaviate)** — points/objects with the row's UUID as the vector ID; embeddings from the `VECTOR` attribute.
- **Key-value (Redis, DynamoDB Local, etcd)** — key = `entityName:uuid`, value = JSON-serialized row.
- **Time-series (Timescale, Influx, QuestDB)** — schema mapped to hypertables / line protocol / ILP; time column derived from a `TIMESTAMP` attribute.
- **Search (Elasticsearch)** — bulk `_bulk` requests with index-per-entity.

Batch sizes come from `BatchSizes.forEngine(DatabaseEngine)` — hand-tuned per engine (Postgres 500, Mongo 1000, Neo4j 200, Elasticsearch 500, ...).

Conflict handling is shared via `ConflictDetector.isConflict(engine, exception)` — the same abstraction across engines maps their native duplicate-key errors to a single "conflict skipped" counter.

---

## Cascade delete

When the user issues a cascade delete, `FrontierBfs` (`engine/driver/support/`) walks the relationship graph one level at a time, driver-side:

1. Start with N root IDs from `EntityIdRegistry` for the requested entity.
2. For each level, translate "children of these parents" into a driver-native query (SQL `WHERE parent_id IN (?, ?, ...)`, Cypher `MATCH (p)-[:HAS]->(c) WHERE p.id IN $ids`, Mongo `find({parent_id: {$in: [...]}})`, ...).
3. Collect IDs, delete them, record per-entity counts and per-level timing in `ScenarioTimings`.

Every level is timed independently. The result surfaces in the UI as a stacked bar — how much of your total delete time was root work vs cascade level 1 vs cascade level 2 vs ...

---

## Scenarios

`engine/scenario/` defines paradigm-flavored workloads:

- **Traversal** — depth-3+ graph walks (parents/children/self-joins); scores graph engines.
- **Aggregate** — group-by + sum/avg over full profiles; scores document engines.
- **Range** — indexed range filters on timestamps/numeric fields; scores relational.
- **KNN** — vector cosine top-K; scores vector engines.

Every scenario runs on every applicable database and results are **canonicalized** by `ResultCanonicalizer` — same-shape output (`List<Map<String, Object>>` sorted by canonical key) so we can detect cross-DB inconsistencies. If two engines return different rows for what should be an equivalent query, `consistencyStatus` in the run response flips from `CONSISTENT` to `DIVERGENT`.

`ScenarioApplicability` decides which scenarios make sense against which engines (no KNN on Postgres unless pgvector is present, no graph traversal on Redis, etc.).

---

## Tests

```bash
./mvnw test
```

205 tests covering cascade planning, data generation, PK vault semantics, faker output, driver connection caches, registry persistence, scenario applicability, timing accumulators. All green.

Formatting: `google-java-format` (AOSP) via Spotless — `./mvnw spotless:apply`.
