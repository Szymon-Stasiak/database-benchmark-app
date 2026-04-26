from __future__ import annotations

import logging

from agents.base import BaseAgent
from models import DatabaseConfig, ValidationResult, ValidationStatus

logger = logging.getLogger("dbagnets")


class DepthCheckerAgent(BaseAgent):

    @property
    def name(self) -> str:
        return "DepthChecker"

    @property
    def role_description(self) -> str:
        return "Checks whether the relationship depth matches the requirements."

    def validate(self, config: DatabaseConfig, script: str) -> ValidationResult:
        system_prompt = f"""You are a data modeling expert. Your task is to analyze
the relationship depth in a database script.

DEFINITION OF RELATIONSHIP DEPTH:
Relationship depth = the longest chain of relationships (FOREIGN KEY / references)
from any root table to a leaf table.

Examples:
- depth=1: A -> B (1 FK relationship, 2 tables)
- depth=2: A -> B -> C (2 FK relationships, 3 tables)
- depth=3: A -> B -> C -> D (3 FK relationships, 4 tables)
- depth=4: A -> B -> C -> D -> E (4 FK relationships, 5 tables)

Junction/pivot tables in M:N relationships DO COUNT as a level.

REQUIRED DEPTH: {config.depth}

Your task is to:
1. Identify ALL tables and their FK relationships
2. Map the relationship graph
3. Find the longest path
4. Check whether the longest path has exactly {config.depth} levels of relationships

RESPOND in this exact format:
STATUS: PASS or FAIL
FEEDBACK: Short description (1-3 sentences) — current depth vs required depth
DETAILS: List of tables and the longest relationship path"""

        user_prompt = (
            f"Required relationship depth: {config.depth}\n\n"
            f"Script to analyze:\n\n{script}"
        )

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