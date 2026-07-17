from __future__ import annotations

import logging

from dbagnets.agents.base import BaseAgent
from dbagnets.models.config import TargetConfig
from dbagnets.models.database_profile import get_profile
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

    def _build_system_prompt(self, target: TargetConfig, schema: LogicalSchema, depth: int) -> str:
        structure_rules = get_profile(target.db_type).structure_rules
        type_hints = format_type_mapping_prompt(target.db_name)
        version_restrictions = self._version_restrictions(target)

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

Use data_size_hints from the LogicalSchema to drive decisions about
index strategies and storage optimization. Treat them as expected production volumes.
For relational databases, do NOT use table partitioning — it forces composite
primary keys that break the benchmark's insert pipeline.

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
12b. FOREIGN KEY COLUMN NAMES (CRITICAL — single source of truth):
   Every non-M:N relationship in the LogicalSchema carries `fk_column_in_child`
   — the EXACT column name that implements the FK on the child entity. The
   matching UUID attribute is ALREADY declared on the child entity. You MUST:
   - Use EXACTLY that column name in the child table / collection / node.
   - NEVER invent a different FK column name (no `owner_ref`, no `fk_owner`,
     no `ownerId` if the schema says `owner_id`).
   - NEVER add an extra FK column the schema does not list as an attribute.
   For relational targets, the FK clause MUST read:
     `FOREIGN KEY (<fk_column_in_child>) REFERENCES <parent> (<parent_pk>)`.
   For graph targets, treat `fk_column_in_child` as the relationship hint and
   OMIT it from node properties (the edge encodes the link, as already stated).
   For document/key-value/vector/time-series, use `fk_column_in_child` as the
   reference field name on the child collection/key.
13. PRODUCTION SCALE DESIGN:
   - Choose index strategies optimized for large datasets (millions of rows).
   - Apply sharding where data_size_hints suggest high volume (relational: no partitioning).
   - Optimize for both write throughput AND read query patterns.
   - Use {target.db_name}'s most advanced features to differentiate it from other DB types.

{type_hints}
{version_restrictions}
{embedding_instruction}
{tool_instruction}"""

    @staticmethod
    def _version_restrictions(target: TargetConfig) -> str:
        notes: list[str] = []

        if target.db_type == DatabaseType.VECTOR and target.db_name.lower() == "milvus":
            version_tuple = tuple(
                int(p) for p in target.db_version.split(".") if p.isdigit()
            ) or (0,)

            if version_tuple < (2, 4):
                notes.append(
                    "- Milvus < 2.4: each Collection MUST have EXACTLY ONE vector field. "
                    "If the LogicalSchema has multiple embedding attributes on one entity, "
                    "keep only the most important embedding in the main collection and put "
                    "each additional embedding in its own separate collection that references "
                    "the parent by id."
                )
            if version_tuple < (2, 3):
                notes.append("- Milvus < 2.3: GPU index types, range search, and upsert are not available.")
            if version_tuple < (2, 2, 9):
                notes.append("- Milvus < 2.2.9: partition_key field is not available — use plain partitions.")

        if not notes:
            return ""

        return (
            "\nVERSION CONSTRAINTS (hard requirements for this specific version):\n"
            + "\n".join(notes)
        )

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
            feedback_text = self._format_feedback_block(feedback)
            return (
                f"{context}\n\n"
                f"Previous script (needs fixing):\n```\n{previous_script}\n```\n\n"
                f"Validator feedback (fix these issues):\n{feedback_text}\n\n"
                "Generate a corrected script addressing all feedback."
            )

        return f"{context}\n\nGenerate a complete database initialization script."
