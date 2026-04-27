from __future__ import annotations

import logging

from dbagnets.agents.base import BaseAgent
from dbagnets.models import DatabaseConfig, DatabaseType, GeneratedScript, ValidationResult

logger = logging.getLogger("dbagnets")


class GeneratorAgent(BaseAgent):

    @property
    def name(self) -> str:
        return "Generator"

    @property
    def role_description(self) -> str:
        return "Generates database initialization scripts based on requirements."

    def generate(
        self,
        config: DatabaseConfig,
        feedback: list[ValidationResult] | None = None,
        previous_script: str | None = None,
    ) -> str:
        system_prompt = self._build_system_prompt(config)
        user_prompt = self._build_user_prompt(config, feedback, previous_script)

        if feedback:
            failed_names = [v.agent_name for v in feedback if not v.passed]
            logger.info("[Generator] Regenerating script with feedback from: %s", ", ".join(failed_names))
        else:
            logger.info("[Generator] Generating initial script")

        result = self._call_llm_structured(
            system_prompt, user_prompt, GeneratedScript, "generate_script"
        )
        script = result.script
        logger.info("[Generator] Generated script: %d chars, %d lines", len(script), script.count("\n") + 1)
        return script

    _STRUCTURE_RULES: dict[DatabaseType, str] = {
        DatabaseType.RELATIONAL: """4. The script must include:
   - CREATE TABLE statements with columns and data types
   - Primary keys and foreign keys (relationships)
   - Indexes where appropriate
   - Constraints (NOT NULL, UNIQUE, CHECK where applicable)
5. Relationship depth must be exactly {depth} levels.
   Depth = the longest chain of FOREIGN KEY relationships.
   E.g. depth=3: Table_A -> Table_B -> Table_C -> Table_D (3 FK hops, 4 tables).""",

        DatabaseType.GRAPH: """4. The script must include:
   - Node label definitions with property constraints
   - Relationship type definitions with property constraints
   - Indexes on frequently queried properties
   - Uniqueness constraints where applicable
5. Relationship depth must be exactly {depth} levels.
   Depth = the longest chain of relationship types between node labels.
   E.g. depth=3: (:A)-[:R1]->(:B)-[:R2]->(:C)-[:R3]->(:D) (3 relationship types, 4 node labels).""",

        DatabaseType.VECTOR: """4. The script must include:
   - Collection definitions with scalar and vector fields
   - Vector index configuration (index type, metric type, dimensions)
   - Primary key / ID field for each collection
   - Partition key definitions where appropriate
5. Relationship depth must be exactly {depth} levels.
   Depth = the longest chain of references between collections.
   E.g. depth=3: Collection_A refs Collection_B refs Collection_C refs Collection_D.""",

        DatabaseType.DOCUMENT: """4. The script must include:
   - Collection/bucket definitions
   - JSON Schema validation rules for document structure
   - Index definitions on frequently queried fields
   - Reference fields (DBRef or manual references) between collections
5. Relationship depth must be exactly {depth} levels.
   Depth = the longest chain of document references between collections.
   E.g. depth=3: Collection_A -> Collection_B -> Collection_C -> Collection_D (3 reference hops).""",

        DatabaseType.KEY_VALUE: """4. The script must include:
   - Key namespace/keyspace definitions
   - Data structure definitions (hashes, lists, sets, sorted sets)
   - TTL/expiration policies where appropriate
   - Secondary index definitions if supported
5. Relationship depth must be exactly {depth} levels.
   Depth = the longest chain of key references between data structures.
   E.g. depth=3: structure_A refs structure_B refs structure_C refs structure_D.""",

        DatabaseType.TIME_SERIES: """4. The script must include:
   - Measurement/hypertable definitions with tags and fields
   - Time-based partitioning configuration
   - Retention policies where appropriate
   - Continuous queries/aggregations if supported
5. Relationship depth must be exactly {depth} levels.
   Depth = the longest chain of relationships between measurements/tables.
   E.g. depth=3: Measurement_A -> Measurement_B -> Measurement_C -> Measurement_D.""",
    }

    def _build_system_prompt(self, config: DatabaseConfig) -> str:
        structure_rules = self._STRUCTURE_RULES[config.db_type].format(depth=config.depth)

        return f"""You are a database expert specializing in {config.db_name} ({config.db_type.value} database).
Your task is to generate a complete, correct initialization script.

RULES:
1. Generate ONLY a clean database script — no explanatory comments, no markdown.
2. The script must be 100% compatible with {config.db_name} version {config.db_version}.
3. Use ONLY syntax and features available in this specific version.
{structure_rules}
6. All entities and relationships must be semantically relevant to the topic: "{config.idea}".
7. Use snake_case naming in English.
8. Do NOT include any sample data. Generate schema/structure definitions only.

Use the generate_script tool to return the complete database script in the "script" field."""

    def _build_user_prompt(
        self,
        config: DatabaseConfig,
        feedback: list[ValidationResult] | None,
        previous_script: str | None,
    ) -> str:
        context = self._build_db_context(config)

        if feedback and previous_script:
            feedback_text = "\n".join(
                f"- [{v.agent_name}] {v.feedback}"
                + (f"\n  Details: {v.details}" if v.details else "")
                for v in feedback if not v.passed
            )
            return (
                f"Requirements:\n{context}\n\n"
                f"Previous script (needs fixing):\n```\n{previous_script}\n```\n\n"
                f"Validator feedback (fix these issues):\n{feedback_text}\n\n"
                "Generate a corrected script addressing all feedback."
            )

        return f"Requirements:\n{context}\n\nGenerate a complete database initialization script."
