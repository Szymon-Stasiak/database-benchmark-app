from __future__ import annotations

import logging

from dbagnets.agents.base import BaseAgent
from dbagnets.models.validation import ValidationResult
from dbagnets.models.validation_context import ValidationContext

logger = logging.getLogger("dbagnets")


class SyntaxCheckerAgent(BaseAgent):

    @property
    def name(self) -> str:
        return "SyntaxChecker"

    @property
    def role_description(self) -> str:
        return "Validates the syntactic correctness of database scripts."

    def validate(self, ctx: ValidationContext) -> ValidationResult:
        target = ctx.target
        assert target is not None and ctx.script is not None
        profile = ctx.profile
        checks = profile.syntax_checks.format(
            db_name=target.db_name, db_version=target.db_version,
        )

        system_prompt = f"""You are a database script syntax validator.
Your ONLY task is to check whether the given script has correct syntax
for {target.db_name} version {target.db_version} ({target.db_type.value} database).

{checks}

NAMESPACE / DATABASE PROVISIONING (CRITICAL — FAIL the script if violated):
The execution environment provides a default scope (e.g. the `benchmark`
database for relational and MongoDB, default Neo4j database). The script
MUST operate inside that scope only and MUST NOT create or switch to a
different namespace. FAIL with a concrete todo if the script contains any of:
  * Relational: `CREATE DATABASE`, `CREATE SCHEMA`, `USE <db>`, `\\c <db>`,
    `SET search_path = ...`, or schema-qualified table names
    (`schema.table` outside of `public`).
  * Graph: `CREATE DATABASE`, `:use <db>`, `USE <db>` directive.
  * Document (MongoDB): `use <db>` at the top of the script.
  * Any other engine: directives that switch keyspace / bucket / index prefix.
If the script lands its tables/collections outside the default scope, every
later insert/query fails with "table/collection does not exist".

Use the validate tool to return your assessment."""

        user_prompt = (
            f"Check the syntax of this script for {target.db_name} {target.db_version}:\n\n{ctx.script}"
        )

        result = self._validate_with_tool_use(system_prompt, user_prompt)
        logger.info("[%s] Result: %s — %s", self.name, result.status.value, result.feedback)
        return result
