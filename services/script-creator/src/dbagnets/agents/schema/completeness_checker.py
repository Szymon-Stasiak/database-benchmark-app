from __future__ import annotations

from dbagnets.agents.base import BaseAgent
from dbagnets.models.schema import LogicalSchema
from dbagnets.models.validation import ValidationResult


class SchemaCompletenessCheckerAgent(BaseAgent):

    @property
    def name(self) -> str:
        return "SchemaCompletenessChecker"

    @property
    def role_description(self) -> str:
        return "Validates that a logical schema covers all essential domain entities."

    def validate(self, schema: LogicalSchema) -> ValidationResult:
        system_prompt = (
            "You are a domain modeling expert.\n"
            "Your task is to verify that the logical schema includes all essential "
            f'entities and attributes for the domain: "{schema.idea}".\n\n'
            "CHECK:\n"
            "1. All core domain entities are present (no major concepts missing).\n"
            "2. Each entity has sufficient attributes to be useful.\n"
            "3. Every entity has a primary key.\n"
            "4. Important relationships between entities exist.\n"
            "5. Data size hints are present and reasonable.\n\n"
            "PASS if the schema is complete enough for a meaningful database.\n"
            "FAIL if critical entities, attributes, or relationships are missing. "
            "List specifically what is missing.\n\n"
            "Use the validate tool to return your assessment."
        )

        schema_json = schema.model_dump_json(indent=2)
        user_prompt = f"Logical Schema:\n{schema_json}"

        return self._validate_with_tool_use(system_prompt, user_prompt)
