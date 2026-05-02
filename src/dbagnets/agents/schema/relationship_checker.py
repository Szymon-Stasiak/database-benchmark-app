from __future__ import annotations

from dbagnets.agents.base import BaseAgent
from dbagnets.models.schema import LogicalSchema
from dbagnets.models.validation import ValidationResult


class SchemaRelationshipCheckerAgent(BaseAgent):

    @property
    def name(self) -> str:
        return "SchemaRelationshipChecker"

    @property
    def role_description(self) -> str:
        return "Validates relationship coherence and referential integrity in a logical schema."

    def validate(self, schema: LogicalSchema) -> ValidationResult:
        entity_names = schema.entity_names

        system_prompt = (
            "You are a data modeling expert.\n"
            "Your task is to verify that all relationships in the logical schema "
            "are coherent and correctly defined.\n\n"
            "CHECK:\n"
            "1. Every relationship references entities that exist in the schema.\n"
            f"   Available entities: {entity_names}\n"
            "2. Cardinalities (1:1, 1:N, M:N) are appropriate for each relationship.\n"
            "3. Relationship names are descriptive and make semantic sense.\n"
            "4. There are no duplicate or contradictory relationships.\n"
            "5. Important connections between entities are not missing.\n\n"
            "PASS if all relationships are valid and coherent.\n"
            "FAIL if any relationship references a non-existent entity, has wrong "
            "cardinality, or if critical relationships are missing.\n\n"
            "Use the validate tool to return your assessment."
        )

        schema_json = schema.model_dump_json(indent=2)
        user_prompt = f"Logical Schema:\n{schema_json}"

        return self._validate_with_tool_use(system_prompt, user_prompt)
