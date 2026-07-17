from __future__ import annotations

from dbagnets.agents.base import BaseAgent
from dbagnets.models.validation import ValidationResult
from dbagnets.models.validation_context import ValidationContext


class NamingConsistencyCheckerAgent(BaseAgent):

    @property
    def name(self) -> str:
        return "NamingConsistencyChecker"

    @property
    def role_description(self) -> str:
        return "Verifies that entity and attribute names in a database script match the LogicalSchema exactly."

    def validate(self, ctx: ValidationContext) -> ValidationResult:
        target = ctx.target
        assert target is not None and ctx.script is not None
        naming_rules = ctx.profile.naming_rules

        entity_list = []
        for entity in ctx.schema.entities:
            attr_names = [a.name for a in entity.attributes]
            entity_list.append(
                f"  - Entity '{entity.name}': attributes = {attr_names}"
            )
        entity_checklist = "\n".join(entity_list)

        system_prompt = f"""You are a database naming consistency expert.
Your task is to verify that a {target.db_name} ({target.db_type.value}) script
uses EXACTLY the same entity names and attribute names as the LogicalSchema.

{naming_rules}

PROCEDURE — check each item below:

1. ENTITY NAME MATCHING:
   For each entity listed below, verify that a structure with that EXACT name
   exists in the script (table, node label, collection, etc.):
{entity_checklist}

2. ATTRIBUTE NAME MATCHING:
   For each entity, verify that EVERY attribute from the list above exists
   in the script with that EXACT name (column, property, field, etc.).
   Count the attributes: the script must have AT LEAST as many as the schema
   (extra attributes like FK columns are allowed).

3. ATTRIBUTE COUNT:
   For each entity, count the schema attributes and the script attributes.
   The script MUST have >= the number of schema attributes for that entity.

FAIL if ANY entity name is missing, renamed, or misspelled.
FAIL if ANY attribute name is missing, renamed, or misspelled.
In your feedback, list every mismatch as: "Entity 'X': expected attribute 'Y', found 'Z'" or "Entity 'X' missing from script".

Use the validate tool to return your assessment."""

        schema_json = ctx.schema.model_dump_json(indent=2)
        user_prompt = (
            f"LogicalSchema:\n{schema_json}\n\n"
            f"Database script for {target.db_name} {target.db_version}:\n\n{ctx.script}"
        )

        return self._validate_with_tool_use(system_prompt, user_prompt)
