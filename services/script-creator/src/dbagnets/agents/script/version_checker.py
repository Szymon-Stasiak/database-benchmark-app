from __future__ import annotations

import logging

from dbagnets.agents.base import BaseAgent
from dbagnets.models import DatabaseConfig, DatabaseType, ValidationResult

logger = logging.getLogger("dbagnets")


class VersionCheckerAgent(BaseAgent):

    @property
    def name(self) -> str:
        return "VersionChecker"

    @property
    def role_description(self) -> str:
        return "Checks whether the script is compatible with the specific database version."

    _EXAMPLES: dict[DatabaseType, str] = {
        DatabaseType.RELATIONAL: """Examples of version incompatibilities:
- GENERATED ALWAYS AS IDENTITY (PostgreSQL < 10)
- CREATE INDEX CONCURRENTLY with IF NOT EXISTS (PostgreSQL < 9.5)
- JSON_TABLE (MySQL < 8.0)
- ON CONFLICT DO UPDATE (PostgreSQL < 9.5)
- CREATE PROCEDURE (PostgreSQL < 11)
- GENERATED ALWAYS AS (stored/virtual) computed columns (PostgreSQL < 12, MySQL < 5.7)
- Declarative table partitioning (PostgreSQL < 10)
- EXCLUSION constraints require btree_gist extension (PostgreSQL)
- CREATE OR REPLACE TRIGGER (PostgreSQL < 14)
- ENUM type variations differ between engines (PostgreSQL CREATE TYPE vs MySQL ENUM column)""",

        DatabaseType.GRAPH: """Examples of version incompatibilities:
- Property existence constraints (IS NOT NULL) and node key constraints
  require Neo4j ENTERPRISE Edition — they FAIL on Community Edition.
  Flag any IS NOT NULL or NODE KEY constraint as FAIL.
- SHOW INDEXES (Neo4j < 4.2)
- Vector indexes (Neo4j < 5.11)
- Full-text indexes via db.index.fulltext.createNodeIndex (Neo4j < 4.0 uses different syntax)
- CREATE CONSTRAINT ... IS UNIQUE new syntax (Neo4j 5.x vs 4.x)
- Point type and spatial indexes (Neo4j < 3.4)
- Composite range indexes (Neo4j < 5.0)
- EXIST → IS NOT NULL constraint rename (Neo4j 5.0)""",

        DatabaseType.VECTOR: """Examples of version incompatibilities:
- GPU index support (Milvus < 2.3)
- JSON field type (Milvus < 2.2)
- Dynamic schema (Milvus < 2.2)
- Range search (Milvus < 2.3)
- Multiple vector fields per collection (Milvus < 2.4)
- Partition key (Milvus < 2.2.9)
- ScaNN index type (Milvus < 2.4)
- Upsert operations (Milvus < 2.3)""",

        DatabaseType.DOCUMENT: """Examples of version incompatibilities:
- JSON Schema validation (MongoDB < 3.6)
- $merge aggregation stage (MongoDB < 4.2)
- Wildcard indexes (MongoDB < 4.2)
- Time series collections (MongoDB < 5.0)
- Clustered indexes on collections (MongoDB < 5.3)
- $densify and $fill operators (MongoDB < 5.3)
- Queryable encryption (MongoDB < 7.0)
- Compound wildcard indexes (MongoDB < 7.0)
- Partial indexes (MongoDB < 3.2)
- Collation support (MongoDB < 3.4)""",

        DatabaseType.KEY_VALUE: """Examples of version incompatibilities:
- Streams (Redis < 5.0)
- ACL (Redis < 6.0)
- JSON module (Redis < 6.2 without RedisJSON)
- Functions (Redis < 7.0)
- GETDEL command (Redis < 6.2)
- OBJECT FREQ (Redis < 4.0)
- Client-side caching (Redis < 6.0)
- Sharded pub/sub (Redis < 7.0)""",

        DatabaseType.TIME_SERIES: """Examples of version incompatibilities:
- Continuous aggregates (TimescaleDB < 1.3)
- Compression (TimescaleDB < 1.5)
- Hierarchical continuous aggregates (TimescaleDB < 2.9)
- Flux language (InfluxDB < 2.0)
- Real-time continuous aggregates (TimescaleDB < 2.7)
- Compression with cagg (TimescaleDB < 2.6)
- Data tiering / tiered storage (TimescaleDB < 2.13)
- add_dimension for multi-dimensional partitioning (TimescaleDB)""",
    }

    def validate(self, config: DatabaseConfig, script: str) -> ValidationResult:
        examples = self._EXAMPLES[config.db_type]

        system_prompt = f"""You are a {config.db_name} version compatibility expert.
Your task is to check whether the script uses ONLY syntax and features
available in {config.db_name} version {config.db_version}.

Check:
1. Whether all data types/field types exist in this version
2. Whether all statements/commands are available in this version
3. Whether all built-in functions/procedures exist in this version
4. Whether schema definition syntax matches this version
5. Whether any features from newer versions are used
6. Whether the script is correct for {config.db_name} (not another database engine!)

{examples}

Use the validate tool to return your assessment."""

        user_prompt = (
            f"Database: {config.db_name} version {config.db_version}\n\n"
            f"Script to check:\n\n{script}"
        )

        result = self._validate_with_tool_use(system_prompt, user_prompt)
        logger.info("[%s] Result: %s — %s", self.name, result.status.value, result.feedback)
        return result
