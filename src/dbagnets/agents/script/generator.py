from __future__ import annotations

import logging

from dbagnets.agents.base import BaseAgent
from dbagnets.models.config import TargetConfig
from dbagnets.models.enums import DatabaseType
from dbagnets.models.llm_schemas import GeneratedScript
from dbagnets.models.schema import LogicalSchema
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
    ) -> str:
        system_prompt = self._build_system_prompt(target, schema, depth)
        user_prompt = self._build_user_prompt(target, schema, idea, depth, feedback, previous_script)

        if feedback:
            failed_names = [v.agent_name for v in feedback if not v.passed]
            logger.info("[ScriptGenerator:%s] Regenerating with feedback from: %s", target.db_name, ", ".join(failed_names))
        else:
            logger.info("[ScriptGenerator:%s] Generating initial script", target.db_name)

        result = self._call_llm_structured(
            system_prompt, user_prompt, GeneratedScript, "generate_script"
        )
        script = result.script
        logger.info("[ScriptGenerator:%s] Generated script: %d chars, %d lines", target.db_name, len(script), script.count("\n") + 1)
        return script

    _STRUCTURE_RULES: dict[DatabaseType, str] = {
        DatabaseType.RELATIONAL: """- CREATE TABLE statements with columns and data types
   - Primary keys and foreign keys implementing all relationships
   - Indexes on all attributes marked as indexed in the schema
   - Constraints (NOT NULL, UNIQUE, CHECK) as defined in the schema
   - Junction tables for M:N relationships
   MAXIMIZE NATIVE FEATURES — use as many of these as the schema allows:
   - CHECK constraints with meaningful domain validation (ranges, patterns, enums)
   - Partial indexes on commonly filtered subsets
   - Functional/expression indexes where queries benefit
   - ENUM types for fixed-set columns instead of plain VARCHAR
   - GENERATED/computed columns for derived values
   - Views for common read patterns (e.g. entity with joined relations)
   - Triggers for audit timestamps (created_at, updated_at auto-population)
   - Table partitioning by range/list for large entities (if data_size_hints suggest it)
   - Sequences for controlled ID generation
   - Domain types for reusable type+constraint combinations
   - EXCLUSION constraints where applicable (e.g. non-overlapping date ranges)""",

        DatabaseType.GRAPH: """- EVERY entity in the LogicalSchema MUST be a separate node label — do NOT
     collapse entities into relationship properties, even for leaf entities.
     This is required to preserve the relationship depth chain.
   - For EACH relationship in the LogicalSchema, the script MUST include either:
     (a) relationship property constraints (e.g. CREATE CONSTRAINT ... FOR ()-[r:REL_TYPE]-() ...)
     (b) relationship property indexes
     (c) or relationship existence constraints
     so that every relationship type is explicitly defined in the DDL.
   - FOREIGN KEY vs PRIMARY KEY distinction (CRITICAL):
     A PRIMARY KEY is an entity's OWN identifier on its OWN node
     (e.g. director_id on Director). PKs MUST be included as node properties.
     A FOREIGN KEY is ANOTHER entity's ID on THIS node (e.g. director_id on
     Movie, user_id on Review). FKs MUST be OMITTED — the relationship
     encodes the link. Only omit FK attributes, NEVER omit PKs.
   - Indexes on frequently queried properties (marked as indexed)
   - Uniqueness constraints for unique/primary key attributes
   - Use PascalCase for node labels and UPPER_SNAKE_CASE for relationship types
   MAXIMIZE NATIVE FEATURES — use as many of these as the schema allows:
   - Full-text indexes on text/string properties that users would search
   - Existence constraints (IS NOT NULL) on all required properties
   - Node key constraints for composite uniqueness
   - Rich relationship properties (e.g. role, weight, timestamp on edges)
     with existence or type constraints on those properties
   - Point/spatial types for geographic data
   - Additional traversal relationships beyond the LogicalSchema to expose
     useful graph navigation paths (e.g. shortcut relationships, reverse lookups)
   - Range indexes vs text indexes — choose the right index type per property
   - Composite indexes on frequently co-queried properties""",

        DatabaseType.VECTOR: """- Collection definitions with scalar and vector fields
   - Vector index configuration (index type, metric type, dimensions)
   - Primary key / ID field for each collection
   - Reference fields implementing relationships between collections
   MAXIMIZE NATIVE FEATURES — use as many of these as the schema allows:
   - Appropriate index type per scale: IVF_FLAT for small, HNSW for latency,
     IVF_SQ8/IVF_PQ for large-scale — choose wisely based on data_size_hints
   - Partition keys for data distribution on large collections
   - Multiple vector fields per collection when entity has different embeddings
   - Scalar indexes on all filter fields for efficient hybrid search
   - Dynamic schema fields where additional metadata varies per record
   - Correct metric type per use case (COSINE for text, L2 for images, IP for recommendations)""",

        DatabaseType.DOCUMENT: """- Collection definitions for top-level entities
   - JSON Schema validation rules matching entity attributes
   - Index definitions on indexed attributes
   - Relationships via embedded sub-documents (preferred for data accessed together)
     or reference fields (for independently queried entities)
   - Denormalization is expected — embed related data for read performance
   MAXIMIZE NATIVE FEATURES — use as many of these as the schema allows:
   - Multi-key indexes on array fields (e.g. tags, categories)
   - Text indexes for full-text search on string/text fields
   - TTL indexes on timestamp fields for data with natural expiration
   - Compound indexes optimized for common query patterns (ESR rule)
   - Partial/sparse indexes for fields that exist only on some documents
   - Wildcard indexes on polymorphic or flexible sub-documents
   - Collation-aware indexes for locale-specific string sorting
   - Schema validation with comprehensive JSON Schema (enum, pattern, min/max)
   - Aggregation pipeline views for common read patterns
   - Capped collections for fixed-size log-like entities""",

        DatabaseType.KEY_VALUE: """- Key namespace/keyspace definitions for each entity
   - Data structure definitions (hashes, lists, sets, sorted sets)
   - Key reference patterns implementing relationships
   - Secondary index definitions for indexed attributes
   MAXIMIZE NATIVE FEATURES — use as many of these as the schema allows:
   - Hashes for entity storage (HSET/HGET for field-level access)
   - Sorted sets for ranked data, leaderboards, time-ordered indexes
   - Sets for unique collections, tags, M:N relationship members
   - Streams for event/activity log entities
   - HyperLogLog for approximate cardinality counting
   - Geospatial commands (GEOADD/GEOSEARCH) for location-based entities
   - TTL policies (EXPIRE) on ephemeral/session-like data
   - Bitmaps for boolean flag matrices
   - Secondary indexes via sorted sets for non-primary lookups
   - Lua scripts for atomic multi-key operations
   - Pub/Sub channels for real-time entity change notifications""",

        DatabaseType.TIME_SERIES: """- Measurement/hypertable definitions with tags and fields
   - Time-based partitioning configuration
   - Relationship implementation via foreign keys or tag references
   - Indexes on indexed attributes
   MAXIMIZE NATIVE FEATURES — use as many of these as the schema allows:
   - Continuous aggregates for pre-computed rollups (hourly, daily summaries)
   - Compression policies on hypertables for storage efficiency
   - Retention policies with automatic data expiration
   - Appropriate chunk interval based on data_size_hints and query patterns
   - Real-time aggregation functions (time_bucket, first, last, interpolate)
   - Data tiering policies (move old data to cheaper storage)
   - Composite indexes on (time, tag) for common time-range + filter queries
   - Downsampling continuous aggregates for long-term trend storage""",
    }

    def _build_system_prompt(self, target: TargetConfig, schema: LogicalSchema, depth: int) -> str:
        structure_rules = self._STRUCTURE_RULES[target.db_type]
        type_hints = format_type_mapping_prompt(target.db_name)

        return f"""You are a database expert specializing in {target.db_name} ({target.db_type.value} database).
Your task is to generate a complete initialization script that FAITHFULLY implements the given LogicalSchema.

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
9. EVERY constraint (primary key, unique, not null, indexed) must be preserved.
10. Use snake_case naming in English.
11. Do NOT include any sample data. Generate schema/structure definitions only.

{type_hints}

Use the generate_script tool to return the complete database script in the "script" field."""

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
