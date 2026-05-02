from __future__ import annotations

import logging

from dbagnets.agents.base import BaseAgent
from dbagnets.models import DatabaseConfig, DatabaseType, ValidationResult

logger = logging.getLogger("dbagnets")


class SyntaxCheckerAgent(BaseAgent):

    @property
    def name(self) -> str:
        return "SyntaxChecker"

    @property
    def role_description(self) -> str:
        return "Validates the syntactic correctness of database scripts."

    _CHECKS: dict[DatabaseType, str] = {
        DatabaseType.RELATIONAL: """Check:
1. Whether each SQL statement has correct syntax (CREATE TABLE, CREATE INDEX, ALTER TABLE, etc.)
2. Whether all parentheses, quotes, and semicolons are properly closed
3. Whether data types are valid for {db_name} {db_version}
4. Whether SQL keywords are correct
5. Whether references to tables/columns in FOREIGN KEY are consistent
6. Whether statement ordering is correct (referenced tables must be created before referencing tables)
7. Whether advanced features use correct syntax: CHECK constraints, ENUM types,
   CREATE VIEW, CREATE TRIGGER, CREATE FUNCTION, GENERATED columns, PARTITION BY,
   CREATE DOMAIN, CREATE SEQUENCE, EXCLUSION constraints, partial indexes""",

        DatabaseType.GRAPH: """Check:
1. Whether each Cypher/graph statement has correct syntax (CREATE, MERGE, constraint definitions)
2. Whether node labels and relationship types follow valid naming rules
3. Whether property types and constraints are valid for {db_name} {db_version}
4. Whether constraint and index definitions reference existing labels/types
5. Whether the script is syntactically complete (no unclosed statements)
6. Whether relationship patterns are correctly formed (e.g. ()-[]->())
7. Whether advanced features use correct syntax: full-text index creation,
   existence constraints, node key constraints, composite indexes,
   point type usage, multiple labels per node""",

        DatabaseType.VECTOR: """Check:
1. Whether collection creation statements have correct syntax for {db_name} {db_version}
2. Whether field definitions are valid (field names, data types, dimensions)
3. Whether vector index parameters are valid (index type, metric type)
4. Whether all referenced collections/fields exist
5. Whether the script uses correct API/DDL syntax for {db_name}
6. Whether required parameters (dimensions, metric type) are specified
7. Whether advanced features use correct syntax: partition key definitions,
   multiple vector fields, dynamic schema config, index parameter tuning
   (nlist, M, efConstruction)""",

        DatabaseType.DOCUMENT: """Check:
1. Whether collection/bucket creation statements have correct syntax for {db_name} {db_version}
2. Whether JSON Schema validation rules are well-formed (valid $jsonSchema)
3. Whether index definitions reference valid fields
4. Whether document references (DBRef or manual) are consistent
5. Whether the script uses correct syntax for {db_name}
6. Whether all required fields in schema definitions are properly typed
7. Whether advanced features use correct syntax: text indexes, TTL indexes,
   partial/sparse indexes, wildcard indexes, compound indexes, collation
   options, capped collection parameters, aggregation pipeline views,
   JSON Schema validation keywords (enum, pattern, minimum, maximum)""",

        DatabaseType.KEY_VALUE: """Check:
1. Whether key/keyspace definitions have correct syntax for {db_name} {db_version}
2. Whether data structure definitions are valid
3. Whether TTL/expiration configurations are syntactically correct
4. Whether index definitions (if applicable) use valid syntax
5. Whether the script uses correct commands/syntax for {db_name}
6. Whether key naming patterns are consistent
7. Whether advanced features use correct syntax: XADD/stream commands,
   PFADD/HyperLogLog, GEOADD/geospatial, SETBIT/bitmaps, sorted set
   operations, EVAL/Lua scripts, SUBSCRIBE/pub-sub""",

        DatabaseType.TIME_SERIES: """Check:
1. Whether measurement/hypertable definitions have correct syntax for {db_name} {db_version}
2. Whether tag and field definitions use valid data types
3. Whether time-based partitioning configuration is syntactically correct
4. Whether retention policies use valid syntax
5. Whether continuous queries/aggregations are well-formed
6. Whether the script uses correct syntax for {db_name} (SQL, Flux, or InfluxQL)
7. Whether advanced features use correct syntax: continuous aggregate definitions,
   compression policies, retention policies, add_compression_policy/
   add_retention_policy, time_bucket functions, data tiering""",
    }

    def validate(self, config: DatabaseConfig, script: str) -> ValidationResult:
        checks = self._CHECKS[config.db_type].format(
            db_name=config.db_name, db_version=config.db_version,
        )

        system_prompt = f"""You are a database script syntax validator.
Your ONLY task is to check whether the given script has correct syntax
for {config.db_name} version {config.db_version} ({config.db_type.value} database).

{checks}

Use the validate tool to return your assessment."""

        user_prompt = f"Check the syntax of this script for {config.db_name} {config.db_version}:\n\n{script}"

        result = self._validate_with_tool_use(system_prompt, user_prompt)
        logger.info("[%s] Result: %s — %s", self.name, result.status.value, result.feedback)
        return result
