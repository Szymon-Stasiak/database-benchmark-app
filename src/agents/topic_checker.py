from __future__ import annotations

import logging

from agents.base import BaseAgent
from models import DatabaseConfig, ValidationResult, ValidationStatus

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

RESPOND in this exact format:
STATUS: PASS or FAIL
FEEDBACK: Short description (1-3 sentences)
DETAILS: Specific notes (what's missing, what's off-topic)"""

        user_prompt = (
            f"Topic/idea: {config.idea}\n"
            f"Database type: {config.db_type.value}\n\n"
            f"Script to evaluate:\n\n{script}"
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