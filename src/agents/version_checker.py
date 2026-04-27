from __future__ import annotations

import logging

from agents.base import BaseAgent
from models import DatabaseConfig, ValidationResult

logger = logging.getLogger("dbagnets")


class VersionCheckerAgent(BaseAgent):

    @property
    def name(self) -> str:
        return "VersionChecker"

    @property
    def role_description(self) -> str:
        return "Checks whether the script is compatible with the specific database version."

    def validate(self, config: DatabaseConfig, script: str) -> ValidationResult:
        system_prompt = f"""You are a database version compatibility expert.
Your task is to check whether the script uses ONLY syntax and features
available in {config.db_name} version {config.db_version}.

Check:
1. Whether all data types exist in this version (e.g. JSONB doesn't exist in PostgreSQL <9.4)
2. Whether all clauses/statements are available in this version
3. Whether all built-in functions exist in this version
4. Whether CREATE TABLE/INDEX/CONSTRAINT syntax matches this version
5. Whether any features from newer versions are used
6. Whether the script is correct for the {config.db_name} engine (not another engine!)

Examples of version incompatibilities:
- GENERATED ALWAYS AS IDENTITY (PostgreSQL < 10)
- CREATE INDEX CONCURRENTLY with IF NOT EXISTS (PostgreSQL < 9.5)
- JSON_TABLE (MySQL < 8.0)
- ON CONFLICT DO UPDATE (PostgreSQL < 9.5)

Use the validate tool to return your assessment."""

        user_prompt = (
            f"Database: {config.db_name} version {config.db_version}\n\n"
            f"Script to check:\n\n{script}"
        )

        result = self._validate_with_tool_use(system_prompt, user_prompt)
        logger.info("[%s] Result: %s — %s", self.name, result.status.value, result.feedback)
        return result
