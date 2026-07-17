from __future__ import annotations

import logging

from dbagnets.agents.base import BaseAgent
from dbagnets.models.validation import ValidationResult
from dbagnets.models.validation_context import ValidationContext

logger = logging.getLogger("dbagnets")


class BestPracticesCheckerAgent(BaseAgent):

    @property
    def name(self) -> str:
        return "BestPracticesChecker"

    @property
    def role_description(self) -> str:
        return "Checks whether the script follows database design best practices."

    def validate(self, ctx: ValidationContext) -> ValidationResult:
        target = ctx.target
        assert target is not None and ctx.script is not None
        practices = ctx.profile.best_practices

        system_prompt = f"""You are a senior {target.db_name} ({target.db_type.value} database) production architect.
Your task is to evaluate whether this script is PRODUCTION-READY for a database
that will hold millions of rows and serve as a benchmark to showcase {target.db_name}'s strengths.

{practices}

IMPORTANT CONSTRAINTS:
- VALUE-CONSTRAINT POLICY (random benchmark data is inserted later — hard FAIL):
  FAIL the script if it contains any of these value-restricting features:
    * UNIQUE constraints outside the PRIMARY KEY itself
      (PRIMARY KEY is implicitly UNIQUE + NOT NULL — never duplicated as UNIQUE)
    * CHECK constraints
    * EXCLUSION constraints
    * ENUM types (PostgreSQL CREATE TYPE ... AS ENUM, MySQL ENUM(...))
    * CREATE DOMAIN (it bundles type + constraints)
    * JSON Schema value validators: enum, pattern, minimum, maximum,
      minLength, maxLength
    * Neo4j uniqueness constraints on non-PK properties
  Only structural constraints are allowed: PRIMARY KEY, FOREIGN KEY,
  NOT NULL, indexes. Random benchmark data must not collide with value
  constraints — violations break the downstream insert benchmark.
- This script implements a LogicalSchema. The set of entities is FIXED.
  Do NOT suggest merging, combining, or removing entities — even if they
  share similar attributes. The LogicalSchema is the source of truth.
- Entity and attribute NAMES come from the LogicalSchema and MUST NOT be renamed.
  If entity names use PascalCase (e.g. "User", "Movie"), that is correct — do NOT
  suggest renaming them to snake_case. The naming consistency is enforced separately.
- This is a schema-only script. Do NOT penalize for missing sample data.
- FAIL only for sections 1-5 (naming, modeling, indexes, constraints/schema,
  data types/design, production scale). These are hard requirements.
- Section 6 (NATIVE FEATURE UTILIZATION) is advisory — NEVER FAIL for it.
  If native features are missing, include suggestions in feedback but PASS.
- Do NOT demand features that go beyond the LogicalSchema (no extra entities,
  no shortcut relationships, no reverse lookups beyond what the schema defines).
- CONVERGENCE RULE: If the script has proper indexes, constraints, and correct
  modeling for sections 1-5, it PASSES. Do not keep raising the bar with new
  demands each iteration — this causes infinite retry loops.

Use the validate tool to return your assessment."""

        user_prompt = (
            f"Database: {target.db_name} {target.db_version}\n"
            f"Topic: {ctx.idea}\n\n"
            f"Script to evaluate:\n\n{ctx.script}"
        )

        result = self._validate_with_tool_use(system_prompt, user_prompt)
        logger.info("[%s] Result: %s — %s", self.name, result.status.value, result.feedback)
        return result
