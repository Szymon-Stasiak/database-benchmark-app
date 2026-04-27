from __future__ import annotations

import logging

from agents.base import BaseAgent
from models import DatabaseConfig, ValidationResult

logger = logging.getLogger("dbagnets")


class BestPracticesCheckerAgent(BaseAgent):

    @property
    def name(self) -> str:
        return "BestPracticesChecker"

    @property
    def role_description(self) -> str:
        return "Checks whether the script follows database design best practices."

    def validate(self, config: DatabaseConfig, script: str) -> ValidationResult:
        system_prompt = f"""You are a database design best practices expert for {config.db_name}.
Your task is to evaluate the script quality against best practices.

Check:
1. NAMING:
   - Consistent snake_case for tables and columns
   - Descriptive names in English
   - Primary keys follow a convention (e.g. id or table_name_id)
   - Foreign keys follow a convention (e.g. referenced_table_id)

2. NORMALIZATION:
   - Schema is at minimum 3NF (third normal form)
   - No data redundancy
   - M:N relationships use junction tables

3. INDEXES:
   - Foreign keys have indexes
   - Frequently searched columns have indexes
   - No excessive indexing

4. CONSTRAINTS:
   - NOT NULL used where appropriate
   - UNIQUE used where needed
   - CHECK constraints for data validation
   - DEFAULT values make sense

5. DATA TYPES:
   - Appropriate types (not VARCHAR(255) for everything)
   - Dates use proper types (TIMESTAMP, DATE)
   - Monetary values use DECIMAL/NUMERIC

NOTE: This is a schema-only script (DDL). There should be NO INSERT statements. Do not penalize for missing sample data.

Use the validate tool to return your assessment."""

        user_prompt = (
            f"Database: {config.db_name} {config.db_version}\n"
            f"Topic: {config.idea}\n\n"
            f"Script to evaluate:\n\n{script}"
        )

        result = self._validate_with_tool_use(system_prompt, user_prompt)
        logger.info("[%s] Result: %s — %s", self.name, result.status.value, result.feedback)
        return result
