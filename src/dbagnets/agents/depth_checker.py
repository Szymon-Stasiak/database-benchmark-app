from __future__ import annotations

import logging

from dbagnets.agents.base import BaseAgent
from dbagnets.models import DatabaseConfig, ValidationResult

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

Use the validate tool to return your assessment."""

        user_prompt = (
            f"Required relationship depth: {config.depth}\n\n"
            f"Script to analyze:\n\n{script}"
        )

        result = self._validate_with_tool_use(system_prompt, user_prompt)
        logger.info("[%s] Result: %s — %s", self.name, result.status.value, result.feedback)
        return result
