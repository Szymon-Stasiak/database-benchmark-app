from __future__ import annotations

import logging

from dbagnets.agents.base import BaseAgent
from dbagnets.models import DatabaseConfig, ValidationResult

logger = logging.getLogger("dbagnets")


class SyntaxCheckerAgent(BaseAgent):

    @property
    def name(self) -> str:
        return "SyntaxChecker"

    @property
    def role_description(self) -> str:
        return "Validates the syntactic correctness of database scripts."

    def validate(self, config: DatabaseConfig, script: str) -> ValidationResult:
        system_prompt = f"""You are a database script syntax validator.
Your ONLY task is to check whether the given script has correct syntax
for {config.db_name} version {config.db_version}.

Check:
1. Whether each statement has correct syntax (CREATE TABLE, INSERT, INDEX, etc.)
2. Whether all parentheses, quotes, and semicolons are properly closed
3. Whether data types are valid for {config.db_name} {config.db_version}
4. Whether keywords are correct
5. Whether references to tables/columns in FOREIGN KEY, INSERT, etc. are consistent
6. Whether statement ordering is correct (cannot INSERT into a table before CREATE)

Use the validate tool to return your assessment."""

        user_prompt = f"Check the syntax of this script for {config.db_name} {config.db_version}:\n\n{script}"

        result = self._validate_with_tool_use(system_prompt, user_prompt)
        logger.info("[%s] Result: %s — %s", self.name, result.status.value, result.feedback)
        return result
