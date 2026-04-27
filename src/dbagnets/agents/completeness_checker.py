from __future__ import annotations

import logging

from dbagnets.agents.base import BaseAgent
from dbagnets.models import DatabaseConfig, ValidationResult

logger = logging.getLogger("dbagnets")


class CompletenessCheckerAgent(BaseAgent):

    @property
    def name(self) -> str:
        return "CompletenessChecker"

    @property
    def role_description(self) -> str:
        return "Checks whether the schema covers all key entities for the given domain."

    def validate(self, config: DatabaseConfig, script: str) -> ValidationResult:
        system_prompt = f"""You are a domain modeling expert. Your task is to evaluate whether
a database schema is COMPLETE for the given topic/idea: "{config.idea}".

Your focus is on MISSING entities, NOT on quality or naming (other validators handle that).

Check:
1. List all key real-world entities that should exist for this domain
2. Check whether each entity is represented in the schema
3. Check whether important attributes for each entity are present
4. Check whether key relationships between entities are modeled
5. Check whether common supporting entities are included (e.g. addresses, categories, tags, audit fields like created_at/updated_at)

Examples of missing entities by domain:
- E-commerce: orders, products, customers, payments, shipping_addresses, categories, reviews
- Social network: users, posts, comments, friendships, likes, messages, groups
- Library: books, authors, members, loans, reservations, genres, publishers
- Hospital: patients, doctors, appointments, departments, prescriptions, medical_records

IMPORTANT:
- This is a schema-only script (DDL). Do NOT penalize for missing sample data.
- FAIL if any core entity for the domain is missing.
- PASS only if the schema reasonably covers the domain.
- In the details field, list which entities are present and which are missing.

Use the validate tool to return your assessment."""

        user_prompt = (
            f"Topic/idea: {config.idea}\n"
            f"Database type: {config.db_type.value}\n"
            f"Database: {config.db_name} {config.db_version}\n\n"
            f"Script to evaluate:\n\n{script}"
        )

        result = self._validate_with_tool_use(system_prompt, user_prompt)
        logger.info("[%s] Result: %s — %s", self.name, result.status.value, result.feedback)
        return result
