from __future__ import annotations

import logging

from dbagnets.agents.base import BaseAgent
from dbagnets.models.validation import ValidationResult
from dbagnets.models.validation_context import ValidationContext

logger = logging.getLogger("dbagnets")


class VersionCheckerAgent(BaseAgent):

    @property
    def name(self) -> str:
        return "VersionChecker"

    @property
    def role_description(self) -> str:
        return "Checks whether the script is compatible with the specific database version."

    def validate(self, ctx: ValidationContext) -> ValidationResult:
        target = ctx.target
        assert target is not None and ctx.script is not None
        examples = ctx.profile.version_examples

        system_prompt = f"""You are a {target.db_name} version compatibility expert.
Your task is to check whether the script uses ONLY syntax and features
available in {target.db_name} version {target.db_version}.

Check:
1. Whether all data types/field types exist in this version
2. Whether all statements/commands are available in this version
3. Whether all built-in functions/procedures exist in this version
4. Whether schema definition syntax matches this version
5. Whether any features from newer versions are used
6. Whether the script is correct for {target.db_name} (not another database engine!)

{examples}

Use the validate tool to return your assessment."""

        user_prompt = (
            f"Database: {target.db_name} version {target.db_version}\n\n"
            f"Script to check:\n\n{ctx.script}"
        )

        result = self._validate_with_tool_use(system_prompt, user_prompt)
        logger.info("[%s] Result: %s — %s", self.name, result.status.value, result.feedback)
        return result
