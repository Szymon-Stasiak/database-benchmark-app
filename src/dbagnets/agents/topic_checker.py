from __future__ import annotations

import logging

from dbagnets.agents.base import BaseAgent
from dbagnets.models import DatabaseConfig, ValidationResult

logger = logging.getLogger("dbagnets")


class TopicCheckerAgent(BaseAgent):

    @property
    def name(self) -> str:
        return "TopicChecker"

    @property
    def role_description(self) -> str:
        return "Checks whether the script is semantically aligned with the described topic."

    def validate(self, config: DatabaseConfig, script: str) -> ValidationResult:
        system_prompt = f"""You are a domain expert. Your task is to evaluate whether a database
script is aligned with the topic/idea: "{config.idea}".

Check:
1. Whether table/collection names are relevant to the topic
2. Whether columns/attributes make sense in the context of the topic
3. Whether relationships between entities are logical for this topic
4. Whether the script covers all key entities that should exist for this topic
5. Whether there are tables/columns that don't belong in this context
6. Whether sample data (INSERTs) is realistic and relevant to the topic

Use the validate tool to return your assessment."""

        user_prompt = (
            f"Topic/idea: {config.idea}\n"
            f"Database type: {config.db_type.value}\n\n"
            f"Script to evaluate:\n\n{script}"
        )

        result = self._validate_with_tool_use(system_prompt, user_prompt)
        logger.info("[%s] Result: %s — %s", self.name, result.status.value, result.feedback)
        return result
