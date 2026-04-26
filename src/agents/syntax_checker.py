from __future__ import annotations

import logging

from agents.base import BaseAgent
from models import DatabaseConfig, ValidationResult, ValidationStatus

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

RESPOND in this exact format:
STATUS: PASS or FAIL
FEEDBACK: Short description (1-3 sentences)
DETAILS: List of specific errors (if FAIL) or "None" (if PASS)"""

        user_prompt = f"Check the syntax of this script for {config.db_name} {config.db_version}:\n\n{script}"

        raw = self._call_llm(system_prompt, user_prompt)
        result = self._parse_result(raw)
        logger.info("[%s] Result: %s — %s", self.name, result.status.value, result.feedback)
        return result

    def _parse_result(self, raw: str) -> ValidationResult:
        status = ValidationStatus.FAIL
        feedback = ""
        details = ""

        for line in raw.split("\n"):
            line_stripped = line.strip()
            if line_stripped.startswith("STATUS:"):
                val = line_stripped.split(":", 1)[1].strip().upper()
                if "PASS" in val:
                    status = ValidationStatus.PASS
            elif line_stripped.startswith("FEEDBACK:"):
                feedback = line_stripped.split(":", 1)[1].strip()
            elif line_stripped.startswith("DETAILS:"):
                details = line_stripped.split(":", 1)[1].strip()

        if not details:
            details = raw

        if not feedback:
            feedback = "Failed to parse validator response." if status == ValidationStatus.FAIL else "OK"

        return ValidationResult(
            agent_name=self.name,
            status=status,
            feedback=feedback,
            details=details,
        )