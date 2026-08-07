# script-creator

Python + FastAPI service that turns a **free-text idea** and a **list of database targets** into:

1. A validated, technology-independent **`LogicalSchema`**.
2. One **initialization script** per target database, faithful to the schema.

Every step is a LangGraph agent loop with generate → validate → refine, running LLM calls through LiteLLM (any provider). The result is deterministic-looking scripts that the backend can ingest, run against fresh containers, and use to shape identical logical data across every engine.

This service **does not measure benchmark time**. That is the backend's job. What this service does is upstream of the timing pipeline: it produces the shared logical model that the backend uses to generate rows and drive all engines from a single source of truth.

---

## Two-phase pipeline

### Phase A — Logical schema (agent loop)

Input: `idea` (free text) + `depth` (longest chain of relationships) + `targets` (list).

An agent generates a `LogicalSchema` — entities, attributes, data types, relationships. Validators check:

- **Deterministic depth check** — graph traversal over the relationship DAG confirms the longest chain matches the requested `depth`.
- **Topic coverage** — LLM validator: do the entities plausibly cover the requested idea?
- **Completeness** — LLM validator: are there enough entities, and do relationships form a coherent world?
- **FK reconciliation** (`orchestrators/fk_reconciler.py`) — every relationship references a real entity/attribute; missing FK columns get added, orphaned references get pruned.

The loop refines the schema until every validator passes or `max_iterations` is hit. Validated schema is emitted as `schema.json` (source of truth) and also returned in the API response.

### Phase B — Per-target scripts (parallel agent loops)

For each requested target, an independent agent loop generates the initialization script. The loop is `orchestrators/script.py` and runs one instance per target — all in parallel via `ThreadPoolExecutor`.

Per-target validators (all subclass `agents/base.py`):

- **Syntax** — engine-native parser check (SQL / Cypher / MongoDB shell / native HTTP body).
- **Version compatibility** — script uses features supported by the requested version (no `MERGE` on Postgres <15, no `withClause` on old Neo4j, no time-series functions on generic Postgres, ...).
- **Depth** — same graph algorithm as Phase A applied to the engine-native artifact.
- **Best practices** — index coverage on FK columns, `NOT NULL` on required attributes, primary key on every table/collection, unique constraints on natural keys.
- **Schema compliance** — the artifact contains every entity, every attribute, every relationship from the validated `LogicalSchema`, with equivalent data types per the engine's mapping table (`models/type_mapping.py`).

Validators run in parallel per target (default). `--sequential` forces serial execution for debugging.

Every validator returns a `ValidationResult(status, feedback, suggested_fixes)`. Failures short-circuit into the next generation pass with **only** the failing feedback and the previous script — full history is deliberately not sent to keep prompt sizes lean and iteration count low.

---

## LLM plumbing

- **LiteLLM** — one call site, any provider. `--model vertex_ai/claude-sonnet-4-6` (default) / `anthropic/claude-sonnet-4-6` / `openai/gpt-4o` / `bedrock/anthropic.claude-3-5-sonnet-...`.
- **Structured output** — every agent uses `BaseAgent._call_llm_structured(prompt, schema=PydanticModel)`, which under the hood forces LiteLLM tool-calling with the Pydantic schema. The provider returns already-validated JSON. **No fragile regex parsing, no hallucinated formats.**
- Pydantic schemas live in `models/llm_schemas.py`.

---

## Public API

```bash
uvicorn dbagnets.api:app --host 0.0.0.0 --port 8001
```

Also started automatically by the repo-root `./start.sh` on port `8001`.

### `POST /generate`

```json
{
  "idea": "movie management system with actors, directors, genres and reviews",
  "depth": 4,
  "targets": [
    {"db_type": "relational", "db_name": "postgresql", "db_version": "16"},
    {"db_type": "graph", "db_name": "neo4j", "db_version": "5.0"},
    {"db_type": "document", "db_name": "mongodb", "db_version": "7.0"}
  ],
  "model": "vertex_ai/claude-sonnet-4-6",
  "max_iterations": 10
}
```

Response:

```json
{
  "success": true,
  "logical_schema": {
    "idea": "...",
    "depth": 4,
    "depth_chain": ["genre", "movie", "review", "user", "watchlist"],
    "entities": [/* LogicalEntity[] */],
    "relationships": [/* LogicalRelationship[] */]
  },
  "scripts": [
    {
      "db_type": "relational",
      "db_name": "postgresql",
      "db_version": "16",
      "container": {
        "docker_image": "postgres:16",
        "default_port": 5432,
        "environment": {"POSTGRES_PASSWORD": "postgres", "POSTGRES_DB": "benchmark"}
      },
      "script": "CREATE TABLE genre (...);\n...",
      "success": true,
      "iterations_used": 3
    }
  ]
}
```

Interactive docs: `http://localhost:8001/docs`.

---

## CLI

```bash
# Single database
python -m dbagnets.main \
  --db-type relational --db-name postgresql --db-version 16 \
  --idea "movie management system" --depth 4 --output init.sql

# Multi-database benchmark
python -m dbagnets.main \
  --idea "e-commerce platform with orders and customers" \
  --depth 4 \
  --target relational:postgresql:16 \
  --target graph:neo4j:5.0 \
  --target document:mongodb:7.0 \
  --output-dir ./out
```

Output layout in benchmark mode:

```
out/
  schema.json              # validated LogicalSchema — source of truth
  postgresql_16.sql
  neo4j_5.0.cypher
  mongodb_7.0.js
```

Full flag reference:

| Flag | Description | Default |
|------|-------------|---------|
| `--idea` | What the database is for. **Required.** | — |
| `--depth` | Longest relationship chain. **Required.** | — |
| `--target TYPE:NAME:VERSION` | Add a target. Repeatable. Activates benchmark mode. | — |
| `--db-type` / `--db-name` / `--db-version` | Single-DB mode | — |
| `--output` / `--output-dir` | File / directory | stdout |
| `--model` | LiteLLM model string | `vertex_ai/claude-sonnet-4-6` |
| `--max-iterations` | Agent-loop iteration ceiling | `10` |
| `--sequential` | Force serial validators | parallel |
| `-v` / `--verbose` | Debug logging | off |

---

## Supported paradigms

| Type          | Example engines                   | Artifact format |
|---------------|-----------------------------------|-----------------|
| `relational`  | PostgreSQL, MySQL                 | `.sql`          |
| `graph`       | Neo4j, ArangoDB, Memgraph         | `.cypher`       |
| `document`    | MongoDB, CouchDB, Elasticsearch   | `.js` / bulk    |
| `vector`      | Qdrant, Weaviate                  | `.py`           |
| `key_value`   | Redis, DynamoDB, etcd             | `.redis` / json |
| `time_series` | TimescaleDB, InfluxDB, QuestDB    | `.sql` / line   |

Adding a new engine: drop a profile into `models/profiles/`, add its type mapping in `models/type_mapping.py`, and register the validators it needs — no orchestrator changes.

---

## Code shape

```
src/dbagnets/
  api.py                    — FastAPI app + endpoints
  main.py                   — CLI entry point
  agents/
    base.py                 — abstract agent contract, _call_llm_structured
    schema/                 — Phase-A agents (schema generator, validators)
    script/                 — Phase-B agents (per-engine script generators, validators)
  orchestrators/
    pipeline.py             — top-level: schema phase → parallel script phase
    schema.py               — LangGraph StateGraph for Phase A
    script.py               — LangGraph StateGraph for Phase B
    fk_reconciler.py        — reconciles relationships against entity attributes
    validator_runner.py     — parallel ThreadPoolExecutor over validators
  models/
    schema.py               — LogicalSchema / LogicalEntity / LogicalAttribute / ...
    llm_schemas.py          — Pydantic schemas used for tool-calling
    type_mapping.py         — LogicalDataType → engine-native type per profile
    profiles/               — per-engine profile files
    state.py                — LangGraph state carriers
```

---

## Design rules

- **Prompts are first-class code.** Version them, test them, measure their output.
- **Parallel by default.** Validators, per-target script generation, LLM calls — all concurrent.
- **Fail fast, fail loud.** Every validator returns actionable feedback. Vague feedback wastes iterations.
- **Small prompts.** Send only the failing feedback + previous artifact. Never the full history.
- **No fragile parsing.** All LLM output flows through Pydantic-typed tool calls.
- **Dependency injection.** Agents receive their model string via constructors; adding a new provider is one arg away.

---

## Tests

```bash
python -m pytest --cov=src/dbagnets --cov-report=term-missing
```
