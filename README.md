# DBagnets

Agent-loop application that generates database initialization scripts using LLMs via LiteLLM (100+ providers). Supports two modes: single-database generation and multi-database benchmark mode with a shared logical schema.

## Modes

### Single-Database Mode

Generates a single initialization script for one database target. The agent loop generates the script, validates it (syntax, version compatibility, depth, topic, completeness, best practices), and refines it until all validators pass.

```bash
# PostgreSQL - movie management system
python -m dbagnets.main \
  --db-type relational \
  --db-name postgresql \
  --db-version 16 \
  --idea "movie management system with actors, directors, genres and reviews" \
  --depth 4 \
  --output init.sql

# Neo4j - social network
python -m dbagnets.main \
  --db-type graph \
  --db-name neo4j \
  --db-version 5.0 \
  --idea "social network for developers with projects, skills and companies" \
  --depth 3 \
  --output social.cypher

# Milvus - image search
python -m dbagnets.main \
  --db-type vector \
  --db-name milvus \
  --db-version 2.3 \
  --idea "image similarity search engine with tags and categories" \
  --depth 2 \
  --output images.py
```

### Benchmark Mode (Multi-Database)

Generates equivalent scripts for multiple database technologies from a single invocation. The pipeline works in two phases:

1. **Phase 1 — Logical Schema**: An agent generates a technology-independent schema (entities, relationships, data types, constraints). It is validated through a loop with deterministic checks (depth via graph algorithm) and LLM validators (topic, completeness, relationships). The validated schema is saved as `schema.json`.

2. **Phase 2 — Parallel Script Generation**: For each target database, an independent agent loop generates a script that faithfully implements the logical schema. Each script is validated for syntax, version compatibility, depth, best practices, and schema compliance (correct entities, equivalent data types, matching indexes and constraints).

```bash
# Generate equivalent scripts for PostgreSQL, Neo4j, and MongoDB
python -m dbagnets.main \
  --idea "movie management system with actors, directors, genres and reviews" \
  --depth 4 \
  --target relational:postgresql:16 \
  --target graph:neo4j:5.0 \
  --target document:mongodb:7.0 \
  --output-dir ./benchmark_output

# With a different model and custom iteration limit
python -m dbagnets.main \
  --idea "e-commerce platform with products, orders, customers and shipping" \
  --depth 4 \
  --target relational:postgresql:16 \
  --target relational:mysql:8.0 \
  --target graph:neo4j:5.0 \
  --target document:mongodb:7.0 \
  --target vector:milvus:2.3 \
  --model openai/gpt-4o \
  --max-iterations 5 \
  --output-dir ./ecommerce_benchmark
```

Output directory structure:
```
benchmark_output/
  schema.json              # Validated logical schema (source of truth)
  postgresql_16.sql        # PostgreSQL initialization script
  neo4j_5.0.cypher         # Neo4j initialization script
  mongodb_7.0.js           # MongoDB initialization script
```

## Options

| Flag | Description | Default |
|------|-------------|---------|
| `--idea` | What the database is for (free text). **Required.** | — |
| `--depth` | Relationship depth — longest chain of relationships between entities. **Required.** | — |
| `--target` | Target database in `TYPE:NAME:VERSION` format (e.g. `relational:postgresql:16`). Repeatable. Activates benchmark mode. | — |
| `--db-type` | Database type (single-DB mode). One of: `relational`, `graph`, `vector`, `document`, `key_value`, `time_series`. | — |
| `--db-name` | Database engine name, e.g. `postgresql`, `neo4j`, `milvus` (single-DB mode). | — |
| `--db-version` | Database engine version, e.g. `16`, `5.0`, `2.3` (single-DB mode). | — |
| `--output` | Save script to file (single-DB mode). Without this flag, the script is printed to stdout. | stdout |
| `--output-dir` | Output directory for benchmark mode. Saves `schema.json` and per-target scripts. Without this flag, output is printed to stdout. | stdout |
| `--max-iterations` | Maximum agent loop iterations before giving up. | `10` |
| `--model` | LiteLLM model string. Supports any provider: `vertex_ai/claude-sonnet-4-6`, `anthropic/claude-sonnet-4-6`, `openai/gpt-4o`, etc. | `vertex_ai/claude-sonnet-4-6` |
| `--sequential` | Run validators sequentially instead of in parallel. Useful for debugging. | parallel |
| `-v`, `--verbose` | Enable debug logging (prompts, token counts, timing details). | off |

## Supported Database Types

| Type | Example engines | Script format |
|------|----------------|---------------|
| `relational` | PostgreSQL, MySQL, SQLite | `.sql` |
| `graph` | Neo4j, Amazon Neptune | `.cypher` |
| `vector` | Milvus, Qdrant | `.py` |
| `document` | MongoDB, CouchDB | `.js` |
| `key_value` | Redis, DynamoDB | `.redis` |
| `time_series` | TimescaleDB, InfluxDB | `.sql` |

## Tests

```bash
python -m pytest --cov=src/dbagnets --cov-report=term-missing
```