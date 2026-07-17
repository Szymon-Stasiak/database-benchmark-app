from __future__ import annotations

from dbagnets.agents.base import BaseAgent
from dbagnets.models.validation import ValidationResult
from dbagnets.models.validation_context import ValidationContext


class SchemaTopicCheckerAgent(BaseAgent):

    @property
    def name(self) -> str:
        return "SchemaTopicChecker"

    @property
    def role_description(self) -> str:
        return "Validates that a logical schema is semantically aligned with its topic."

    def validate(self, ctx: ValidationContext) -> ValidationResult:
        schema = ctx.schema
        system_prompt = (
            "You are a domain modeling expert.\n"
            "Your task is to verify that the following logical schema is semantically "
            f'aligned with the topic: "{schema.idea}".\n\n'
            "CHECK:\n"
            "1. Entity names are relevant to the domain.\n"
            "2. Attributes make sense for their entities in this domain.\n"
            "3. Relationships logically connect domain concepts.\n"
            "4. There are no entities or relationships that are irrelevant to the topic.\n\n"
            "PASS if the schema is well-aligned with the topic.\n"
            "FAIL if any entity, attribute, or relationship is irrelevant or missing "
            "critical domain concepts.\n\n"
            "Use the validate tool to return your assessment."
        )

        schema_json = schema.model_dump_json(indent=2)
        user_prompt = f"Logical Schema:\n{schema_json}"

        return self._validate_with_tool_use(system_prompt, user_prompt)
