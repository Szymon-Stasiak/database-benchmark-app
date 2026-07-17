from __future__ import annotations

from dbagnets.agents.base import BaseAgent
from dbagnets.models.type_mapping import format_type_mapping_prompt
from dbagnets.models.validation import ValidationResult
from dbagnets.models.validation_context import ValidationContext


class SchemaComplianceCheckerAgent(BaseAgent):

    @property
    def name(self) -> str:
        return "SchemaComplianceChecker"

    @property
    def role_description(self) -> str:
        return "Verifies that a database script faithfully implements a LogicalSchema."

    def validate(self, ctx: ValidationContext) -> ValidationResult:
        target = ctx.target
        assert target is not None and ctx.script is not None
        type_hints = format_type_mapping_prompt(target.db_name)
        compliance_rules = ctx.profile.compliance_rules

        system_prompt = f"""You are a database schema compliance expert.
Your task is to verify that a database script FAITHFULLY implements
a given LogicalSchema for {target.db_name} ({target.db_type.value}).

{compliance_rules}

CHECK EACH OF THESE:

1. ENTITY COVERAGE:
   Every entity in the LogicalSchema must be represented in the script
   according to the database-specific rules above.

2. ATTRIBUTE COVERAGE:
   Every attribute of every entity must be present with an equivalent data type.
   {type_hints}

3. RELATIONSHIP COVERAGE (MOST IMPORTANT):
   Go through EVERY relationship in the LogicalSchema one by one.
   For each relationship verify:
   - It exists in the script (as FK, relationship type, reference, embedded doc, etc.)
   - The direction (source -> target) is correct
   - The cardinality (1:1, 1:N, M:N) is correctly implemented
   - For M:N: a junction table/collection or equivalent mechanism exists
   - For 1:N: a foreign key, reference, or relationship connecting both entities exists
   List each relationship by name and mark it as FOUND or MISSING.

4. CONSTRAINT PRESERVATION:
   Primary keys, unique constraints, NOT NULL constraints, and indexes
   must be preserved according to the database-specific rules above.

5. VECTOR HANDLING:
   If any attribute has type VECTOR, verify the script uses the
   appropriate vector type/extension for {target.db_name}.

FAIL if any entity, attribute, relationship, or constraint
from the LogicalSchema is missing or incorrectly mapped
(accounting for the database-specific rules above).
In your feedback, list every MISSING relationship by name so the
generator knows exactly what to fix.

Use the validate tool to return your assessment."""

        schema_json = ctx.schema.model_dump_json(indent=2)
        user_prompt = (
            f"LogicalSchema:\n{schema_json}\n\n"
            f"Database script for {target.db_name} {target.db_version}:\n\n{ctx.script}"
        )

        return self._validate_with_tool_use(system_prompt, user_prompt)
