from __future__ import annotations

import logging

from dbagnets.agents.base import BaseAgent
from dbagnets.models import DatabaseConfig, GeneratedScript, ValidationResult

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

    def _build_system_prompt(self, config: DatabaseConfig) -> str:
        return f"""You are a database expert. Your task is to generate complete, correct database
initialization scripts.

RULES:
1. Generate ONLY a clean database script — no explanatory comments, no markdown.
2. The script must be 100% compatible with {config.db_name} version {config.db_version}.
3. Use ONLY syntax and features available in this specific version.
4. The script must include:
   - Creation of tables/collections/nodes (depending on database type)
   - Primary and foreign keys (relationships)
   - Indexes where appropriate
   - Constraints (NOT NULL, UNIQUE, CHECK where applicable)
   - Sensible data types
5. Relationship depth must be exactly {config.depth} levels.
   Depth = the longest path of relationships from a root table to a leaf table.
   E.g. depth=3 means: Table_A -> Table_B -> Table_C -> Table_D (3 FK relationships, 4 tables).
6. Tables, columns, and relationships must be semantically relevant to the topic: "{config.idea}".
7. Use snake_case naming in English.
8. Do NOT include any INSERT statements or sample data. Generate schema only (DDL).

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
