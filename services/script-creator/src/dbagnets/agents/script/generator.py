from __future__ import annotations

import logging

from dbagnets.agents.base import BaseAgent
from dbagnets.models.config import TargetConfig
from dbagnets.models.enums import DatabaseType
from dbagnets.models.llm_schemas import GeneratedScript
from dbagnets.models.schema import DocumentEmbeddingMapping, LogicalSchema
from dbagnets.models.type_mapping import format_type_mapping_prompt
from dbagnets.models.validation import ValidationResult

logger = logging.getLogger("dbagnets")


class ScriptGeneratorAgent(BaseAgent):

    @property
    def name(self) -> str:
        return "ScriptGenerator"

    @property
    def role_description(self) -> str:
        return "Generates database-specific scripts from a logical schema."

    def generate(
        self,
        target: TargetConfig,
        schema: LogicalSchema,
        idea: str,
        depth: int,
        feedback: list[ValidationResult] | None = None,
        previous_script: str | None = None,
    ) -> tuple[str, list[DocumentEmbeddingMapping]]:
        system_prompt = self._build_system_prompt(target, schema, depth)
        user_prompt = self._build_user_prompt(target, schema, idea, depth, feedback, previous_script)

        if feedback:
            failed_names = [v.agent_name for v in feedback if not v.passed]
            logger.info("[ScriptGenerator:%s] Regenerating with feedback from: %s", target.db_name, ", ".join(failed_names))
        else:
            logger.info("[ScriptGenerator:%s] Generating initial script", target.db_name)

        result = self._call_llm_structured(
            system_prompt, user_prompt, GeneratedScript, "generate_script",
            max_tokens=16384,
        )
        script = result.script
        logger.info("[ScriptGenerator:%s] Generated script: %d chars, %d lines", target.db_name, len(script), script.count("\n") + 1)

        mappings: list[DocumentEmbeddingMapping] = []
        if target.db_type == DatabaseType.DOCUMENT and result.embedding_mappings:
            mappings = [
                DocumentEmbeddingMapping(
                    entity_name=m.entity_name,
                    is_embedded=m.is_embedded,
                    parent_entity=m.parent_entity,
                    field_name=m.field_name,
                )
                for m in result.embedding_mappings
            ]
            embedded = [m.entity_name for m in mappings if m.is_embedded]
            logger.info("[ScriptGenerator:%s] Embedding mappings: %d entities, %d embedded", target.db_name, len(mappings), len(embedded))

        return script, mappings

    _STRUCTURE_RULES: dict[DatabaseType, str] = {
        DatabaseType.RELATIONAL: """- CREATE TABLE statements with columns and data types
   - Primary keys and foreign keys implementing all relationships
   - Indexes on all attributes marked as indexed in the schema
   - NOT NULL constraints as defined in the schema (no UNIQUE outside PK, no CHECK)
   - Junction tables for M:N relationships
   CONSTRAINT POLICY (CRITICAL — random benchmark data is inserted later):
   - ONLY structural constraints are allowed: PRIMARY KEY, FOREIGN KEY, NOT NULL.
   - Do NOT emit: UNIQUE (outside the PK itself), CHECK, EXCLUSION, ENUM types
     (CREATE TYPE ... AS ENUM, MySQL ENUM(...)), CREATE DOMAIN.
   - Value-restricting constraints break random-data benchmarks downstream.
   PRODUCTION-SCALE DESIGN:
   - Table partitioning (RANGE by date, LIST by category) for entities with high data_size_hints
     CRITICAL PARTITIONING RULE (PostgreSQL hard requirement, no exceptions):
       Every PRIMARY KEY on a partitioned table MUST contain ALL partition-key columns.
       PostgreSQL rejects anything else with the error
       "unique constraint on partitioned table must include all partitioning columns".
       Therefore:
         * Use a COMPOSITE PRIMARY KEY that includes the surrogate id AND the partition
           column, e.g. `PRIMARY KEY (id, created_at)` when `PARTITION BY RANGE (created_at)`.
         * NEVER write `id SERIAL PRIMARY KEY` together with `PARTITION BY (other_col)`.
       Foreign keys to a partitioned parent must reference the full composite key
       (e.g. `FOREIGN KEY (parent_id, parent_created_at) REFERENCES parent(id, created_at)`).
       If propagating the partition column to children is impractical, drop the FK on
       that relationship — it's acceptable for benchmark schemas.
     STORAGE PARAMETERS + PARTITIONED TABLES (PostgreSQL hard requirement):
       PostgreSQL FORBIDS storage parameters (FILLFACTOR, autovacuum_*, toast.*,
       parallel_workers, etc.) on partitioned parent tables — both inline
       `WITH (...)` and `ALTER TABLE <parent> SET (...)` raise
       "cannot specify storage parameters for a partitioned table".
       Rules:
         * Do NOT use `WITH (fillfactor=...)` on a CREATE TABLE that has PARTITION BY.
         * Do NOT use `ALTER TABLE <parent> SET (fillfactor=...)` on a partitioned parent.
         * If FILLFACTOR is desired for a partitioned entity, apply it per partition:
           `ALTER TABLE <partition_name> SET (fillfactor=...)` for each leaf partition.
         * Simplest: skip FILLFACTOR entirely on partitioned tables.
       Use FILLFACTOR freely on non-partitioned tables (via ALTER TABLE after creation).
     MYSQL: FULLTEXT INDEX + PARTITIONING (hard requirement):
       MySQL FORBIDS `FULLTEXT INDEX` (and `SPATIAL INDEX`) on partitioned tables —
       both inline on CREATE TABLE and via ALTER TABLE on a partitioned parent —
       erroring with "ER_PARTITION_FULLTEXT_NOT_SUPPORTED" (error 1214).
       Rules:
         * Do NOT add `FULLTEXT INDEX ...` or `FULLTEXT KEY ...` on any CREATE TABLE
           that contains `PARTITION BY ...`, and do NOT add it via ALTER TABLE either.
         * If full-text search on a high-volume entity is desirable, either drop
           the partitioning, or skip the FULLTEXT index for that entity.
       Same restriction applies to SPATIAL indexes on partitioned tables.
   - Covering indexes (INCLUDE columns) for frequently queried combinations
   - Partial indexes on commonly filtered subsets (e.g. WHERE status = 'active')
   - Functional/expression indexes (e.g. LOWER(email)) for case-insensitive lookups
   - B-tree for equality/range, GIN for arrays/JSONB/full-text, GiST for spatial/ranges
   MAXIMIZE NATIVE FEATURES — use as many of these as the schema allows:
   - GENERATED/computed columns for derived values
   - Materialized views or regular views for common multi-table read patterns
   - Triggers for audit timestamps (created_at, updated_at auto-population)
   - Sequences for controlled ID generation
   - FILLFACTOR tuning on tables with frequent updates""",

        DatabaseType.GRAPH: """- EVERY entity in the LogicalSchema MUST be a separate node label — do NOT
     collapse entities into relationship properties, even for leaf entities.
     This is required to preserve the relationship depth chain.
   - For EACH relationship in the LogicalSchema, the script MUST include either:
     (a) relationship property constraints (e.g. CREATE CONSTRAINT ... FOR ()-[r:REL_TYPE]-() ...)
     (b) relationship property indexes
     so that every relationship type is explicitly defined in the DDL.
   - FOREIGN KEY vs PRIMARY KEY distinction (CRITICAL):
     A PRIMARY KEY is an entity's OWN identifier on its OWN node
     (e.g. director_id on Director). PKs MUST be included as node properties.
     A FOREIGN KEY is ANOTHER entity's ID on THIS node (e.g. director_id on
     Movie, user_id on Review). FKs MUST be OMITTED — the relationship
     encodes the link. Only omit FK attributes, NEVER omit PKs.
   - Indexes on frequently queried properties (marked as indexed)
   - Uniqueness constraints ONLY for primary-key attributes
     (no uniqueness on natural identifiers like email/slug — random benchmark
      data must not collide with value constraints)
   - Use PascalCase for node labels and UPPER_SNAKE_CASE for relationship types
   NEO4J COMMUNITY EDITION LIMITATION (CRITICAL):
   - Property existence constraints (IS NOT NULL) and node key constraints
     require Neo4j Enterprise Edition. NEVER use them — they will cause runtime
     errors on Community Edition. Use uniqueness constraints and indexes only.
   PRODUCTION-SCALE DESIGN:
   - Range indexes on properties used in WHERE clauses and ORDER BY
   - Text indexes on properties used in full-text search (CONTAINS, STARTS WITH)
   - Composite indexes on properties frequently queried together
   MAXIMIZE NATIVE FEATURES — show what graphs do better than relational:
   - Rich relationship properties (e.g. role, weight, timestamp on edges)
     with indexes on those properties
   - Point/spatial types for geographic data
   - Additional traversal relationships beyond the LogicalSchema to expose
     useful graph navigation paths (e.g. shortcut relationships, reverse lookups)
   - Full-text indexes on text/string properties that users would search""",

        DatabaseType.VECTOR: """- Collection definitions with scalar and vector fields
   - Vector index configuration (index type, metric type, dimensions)
   - Primary key / ID field for each collection
   - Reference fields implementing relationships between collections
   PRODUCTION-SCALE DESIGN:
   - Choose index type based on data_size_hints: IVF_FLAT (<100K rows), HNSW (<10M, latency-critical),
     IVF_SQ8/IVF_PQ (>10M rows, memory-constrained) — match the scale
   - Tune index params: HNSW (M=16-64, efConstruction=200-500), IVF (nlist=sqrt(N))
   - Partition keys for data distribution on collections with >1M rows
   - Scalar indexes on ALL filter fields — hybrid search (vector + filter) is the primary use case
   MAXIMIZE NATIVE FEATURES — show what vector DBs do better:
   - Multiple vector fields per collection when entity has different embeddings
   - Correct metric type per use case (COSINE for text, L2 for images, IP for recommendations)
   - Dynamic schema fields where additional metadata varies per record
   - Consistency level configuration for read/write tradeoffs""",

        DatabaseType.DOCUMENT: """- Collection definitions for top-level entities
   - Index definitions on indexed attributes
   - Relationships via embedded sub-documents (preferred for data accessed together)
     or reference fields (for independently queried entities)
   - Denormalization is expected — embed related data for read performance
   CONSTRAINT POLICY (CRITICAL — random benchmark data is inserted later):
   - JSON Schema validation is OPTIONAL and, if present, MUST be type-only
     (bsonType / required). Do NOT use enum, pattern, minimum, maximum,
     minLength, maxLength — value validators break random-data benchmarks.
   PRODUCTION-SCALE DESIGN:
   - Compound indexes following ESR rule (Equality, Sort, Range) for query optimization
   - Covered queries: include all projected fields in indexes where possible
   - Partial/sparse indexes for optional fields to save space at scale
   - Shard key design: choose high-cardinality fields for even data distribution
   - Read concern/write concern configuration for consistency vs performance tradeoffs
   MAXIMIZE NATIVE FEATURES — show what document DBs do better:
   - Multi-key indexes on array fields (e.g. tags, categories)
   - Text indexes for full-text search on string/text fields
   - TTL indexes on timestamp fields for data with natural expiration
   - Wildcard indexes on polymorphic or flexible sub-documents
   - Collation-aware indexes for locale-specific string sorting
   - Aggregation pipeline views for common read patterns
   - Change streams configuration for real-time data processing
   - Capped collections for fixed-size log-like entities""",

        DatabaseType.KEY_VALUE: """- Key namespace/keyspace definitions for each entity
   - Data structure definitions (hashes, lists, sets, sorted sets)
   - Key reference patterns implementing relationships
   - Secondary index definitions for indexed attributes
   PRODUCTION-SCALE DESIGN:
   - Memory-efficient encoding: use hashes for objects (ziplist encoding for small hashes)
   - Key expiration strategies: volatile-lru, allkeys-lfu depending on use case
   - Pipeline-friendly key design: namespace:entity_id pattern for batch operations
   - Secondary indexes via sorted sets for non-primary lookups at scale
   MAXIMIZE NATIVE FEATURES — show what key-value stores do better:
   - Hashes for entity storage (HSET/HGET for field-level access)
   - Sorted sets for ranked data, leaderboards, time-ordered indexes
   - Sets for unique collections, tags, M:N relationship members
   - Streams with consumer groups for event/activity log entities
   - HyperLogLog for approximate cardinality counting (unique visitors, etc.)
   - Geospatial commands (GEOADD/GEOSEARCH) for location-based entities
   - TTL policies (EXPIRE) on ephemeral/session-like data
   - Bitmaps for boolean flag matrices (feature flags, permissions)
   - Lua scripts for atomic multi-key operations
   - Pub/Sub channels for real-time entity change notifications""",

        DatabaseType.TIME_SERIES: """- Measurement/hypertable definitions with tags and fields
   - Time-based partitioning configuration
   - Relationship implementation via foreign keys or tag references
   - Indexes on indexed attributes
   PRODUCTION-SCALE DESIGN:
   - Chunk interval tuned to data_size_hints: 1 day for high-frequency, 1 week for low-frequency
   - Compression policies with segmentby (tags) and orderby (time) for 90%+ compression
   - Retention policies to automatically drop data older than business requirements
   - Composite indexes on (time, tag columns) for the primary query pattern: time-range + filter
   MAXIMIZE NATIVE FEATURES — show what time-series DBs do better:
   - Continuous aggregates for pre-computed rollups (hourly, daily, weekly summaries)
   - Real-time aggregation functions (time_bucket, first, last, interpolate)
   - Hierarchical continuous aggregates (minute → hour → day)
   - Data tiering policies (move old data to cheaper storage)
   - Downsampling continuous aggregates for long-term trend storage
   - Space-partitioning with add_dimension for multi-tenant or multi-device data""",
    }

    def _build_system_prompt(self, target: TargetConfig, schema: LogicalSchema, depth: int) -> str:
        structure_rules = self._STRUCTURE_RULES[target.db_type]
        type_hints = format_type_mapping_prompt(target.db_name)

        embedding_instruction = ""
        if target.db_type == DatabaseType.DOCUMENT:
            embedding_instruction = """
EMBEDDING MAPPING (required for document databases):
For each entity in the LogicalSchema, fill the "embedding_mappings" array:
- entity_name: exact entity name from the LogicalSchema
- is_embedded: true if stored as a sub-document within another collection, false if top-level collection
- parent_entity: if embedded, the name of the parent entity (must be another LogicalSchema entity)
- field_name: if embedded, the field/key name used in the parent document (snake_case)
Every LogicalSchema entity MUST appear exactly once in this array.
"""

        tool_instruction = 'Use the generate_script tool to return the complete database script in the "script" field.'
        if target.db_type == DatabaseType.DOCUMENT:
            tool_instruction += '\nAlso fill the "embedding_mappings" array with the embedding mapping for each entity.'

        return f"""You are a senior database architect specializing in {target.db_name} ({target.db_type.value} database).

CONTEXT: This script is for a PRODUCTION-GRADE database that will hold millions of rows
and serve as part of a cross-database benchmark. The goal is to showcase {target.db_name}'s
UNIQUE STRENGTHS compared to other database types. Design the schema as if a team of
expert DBAs reviewed it for performance at scale.

Use data_size_hints from the LogicalSchema to drive decisions about partitioning,
index strategies, and storage optimization. Treat them as expected production volumes.

Your task is to generate a complete initialization script that FAITHFULLY implements
the given LogicalSchema while maximizing {target.db_name}'s native capabilities.

RULES:
1. Generate ONLY a clean database script — no explanatory comments, no markdown.
2. The script must be 100% compatible with {target.db_name} version {target.db_version}.
3. Use ONLY syntax and features available in this specific version.
4. The script must include:
   {structure_rules}
5. CRITICAL — Relationship depth must be EXACTLY {depth} levels.
   Do NOT remove or merge entities that form the depth chain.
   Every entity in the LogicalSchema exists for a reason — preserve all of them.
6. EVERY entity in the LogicalSchema must have a corresponding structure in the script.
7. EVERY attribute must be present with an equivalent data type.
8. EVERY relationship must be implemented appropriately for this database type.
9. CONSTRAINT POLICY — random benchmark data is inserted later, so the script
   MUST NOT contain value-restricting constraints:
   - Preserve: PRIMARY KEY, FOREIGN KEY, NOT NULL, indexed markers.
   - Do NOT emit: UNIQUE (outside the PK itself), CHECK, EXCLUSION constraints,
     ENUM types (CREATE TYPE ... AS ENUM, MySQL ENUM(...)), CREATE DOMAIN,
     or JSON Schema value validators (enum, pattern, minimum, maximum,
     minLength, maxLength).
   PRIMARY KEY is implicitly UNIQUE + NOT NULL — never duplicate it as a
   separate UNIQUE constraint. FK references a unique parent PK by definition.
10. Use snake_case naming in English.
11. Do NOT include any sample data. Generate schema/structure definitions only.
11b. NAMESPACE / DATABASE PROVISIONING (CRITICAL):
    The execution environment (Docker container) provides a default working
    scope and the script runner connects to it explicitly. The script MUST
    operate ONLY within that default scope and MUST NOT create, switch, or
    reference any alternate database/schema/keyspace.
    Forbidden statements (PER engine):
      * Relational (PostgreSQL / MySQL / TimescaleDB):
          NO `CREATE DATABASE`, NO `CREATE SCHEMA`, NO `USE <db>`,
          NO `\\c <db>`, NO `SET search_path = ...`, NO schema-qualified
          identifiers like `myschema.mytable` — use bare table names.
      * Graph (Neo4j / Memgraph):
          NO `CREATE DATABASE`, NO `:use <db>`, NO `USE <db>`.
      * Document (MongoDB):
          NO `use <db>` directive at the top of the script.
          Use bare collection references like `db.collection_name.<op>()` —
          `db` is already bound to the correct working database.
      * Key-value / time-series / vector / HTTP-API engines:
          NO bucket / keyspace / index-prefix creation that overrides the
          container's provided default.
    Rationale: downstream insert/benchmark code connects to the
    container-provided default (e.g. `benchmark` for SQL, `benchmark` MongoDB
    db, default Neo4j database). If the script lands its tables/collections
    elsewhere, every later query fails with "table/collection does not exist".
12. NAMING CONSISTENCY (CRITICAL):
   Entity names in the script MUST match the LogicalSchema EXACTLY (same spelling, same case).
   Attribute names in the script MUST match the LogicalSchema EXACTLY.
   Do NOT rename, abbreviate, or translate any name from the schema.
   The script will be used for automated benchmarking where queries are generated
   programmatically from the schema — any name mismatch will break the pipeline.
13. PRODUCTION SCALE DESIGN:
   - Choose index strategies optimized for large datasets (millions of rows).
   - Apply partitioning/sharding where data_size_hints suggest high volume.
   - Optimize for both write throughput AND read query patterns.
   - Use {target.db_name}'s most advanced features to differentiate it from other DB types.

{type_hints}
{embedding_instruction}
{tool_instruction}"""

    def _build_user_prompt(
        self,
        target: TargetConfig,
        schema: LogicalSchema,
        idea: str,
        depth: int,
        feedback: list[ValidationResult] | None,
        previous_script: str | None,
    ) -> str:
        schema_json = schema.model_dump_json(indent=2)
        context = (
            f"Database: {target.db_name} {target.db_version} ({target.db_type.value})\n"
            f"Idea: {idea}\n"
            f"Required relationship depth: {depth}\n\n"
            f"LogicalSchema to implement:\n{schema_json}"
        )

        if feedback and previous_script:
            feedback_parts = []
            for v in feedback:
                if v.passed:
                    continue
                part = f"- [{v.agent_name}] {v.feedback}"
                if v.todos:
                    part += "\n  TODO:\n" + "\n".join(f"    - {t}" for t in v.todos)
                elif v.details:
                    part += f"\n  Details: {v.details}"
                feedback_parts.append(part)
            feedback_text = "\n".join(feedback_parts)
            return (
                f"{context}\n\n"
                f"Previous script (needs fixing):\n```\n{previous_script}\n```\n\n"
                f"Validator feedback (fix these issues):\n{feedback_text}\n\n"
                "Generate a corrected script addressing all feedback."
            )

        return f"{context}\n\nGenerate a complete database initialization script."
