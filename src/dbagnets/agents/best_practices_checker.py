from __future__ import annotations

import logging

from dbagnets.agents.base import BaseAgent
from dbagnets.models import DatabaseConfig, DatabaseType, ValidationResult

logger = logging.getLogger("dbagnets")


class BestPracticesCheckerAgent(BaseAgent):

    @property
    def name(self) -> str:
        return "BestPracticesChecker"

    @property
    def role_description(self) -> str:
        return "Checks whether the script follows database design best practices."

    _PRACTICES: dict[DatabaseType, str] = {
        DatabaseType.RELATIONAL: """Check:
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
   - Monetary values use DECIMAL/NUMERIC""",

        DatabaseType.GRAPH: """Check:
1. NAMING:
   - Node labels use PascalCase (e.g. Movie, Actor)
   - Relationship types use UPPER_SNAKE_CASE (e.g. ACTED_IN, DIRECTED)
   - Properties use camelCase or snake_case consistently
   - Descriptive names in English

2. MODELING:
   - Relationships are used instead of join-node patterns where appropriate
   - Properties are on the correct entity (node vs relationship)
   - No unnecessary intermediate nodes that should be relationships
   - Rich relationship properties where applicable

3. INDEXES & CONSTRAINTS:
   - Unique constraints on natural identifiers
   - Indexes on frequently queried properties
   - Node key constraints where composite uniqueness is needed

4. PROPERTIES:
   - Appropriate property types (not strings for everything)
   - Existence constraints on required properties
   - Temporal properties use proper types""",

        DatabaseType.VECTOR: """Check:
1. NAMING:
   - Consistent snake_case for collection and field names
   - Descriptive names in English
   - Clear distinction between scalar and vector fields

2. VECTOR DESIGN:
   - Appropriate vector dimensions for the use case
   - Correct metric type (L2, IP, COSINE) for the data
   - Suitable index type (IVF_FLAT, HNSW, etc.) for the scale

3. SCHEMA:
   - Primary key field defined
   - Partition key chosen for data distribution if applicable
   - Scalar fields for filtering are properly typed

4. INDEXES:
   - Vector indexes configured with appropriate parameters
   - Scalar indexes on filter fields""",

        DatabaseType.DOCUMENT: """Check:
1. NAMING:
   - Consistent snake_case for collection and field names
   - Descriptive names in English
   - Clear naming for reference fields

2. MODELING:
   - Appropriate use of embedding vs referencing
   - Frequently accessed together data is embedded
   - Large or independently queried data uses references
   - No excessive nesting depth in embedded documents

3. INDEXES:
   - Indexes on frequently queried fields
   - Compound indexes for common query patterns
   - No excessive indexing

4. SCHEMA VALIDATION:
   - Required fields are enforced
   - Field types are specified
   - Enum values are constrained where appropriate""",

        DatabaseType.KEY_VALUE: """Check:
1. NAMING:
   - Consistent key naming convention (e.g. entity:id:field)
   - Descriptive names in English
   - Namespace separation between entity types

2. DATA STRUCTURES:
   - Appropriate structure choice (hash, list, set, sorted set)
   - Hashes for object-like data
   - Sets/sorted sets for relationships and indexes
   - Lists for ordered sequences

3. DESIGN:
   - Secondary indexes implemented where needed
   - TTL policies on ephemeral data
   - Key references are consistent

4. EFFICIENCY:
   - No overly large keys or values
   - Appropriate granularity (not too fine, not too coarse)""",

        DatabaseType.TIME_SERIES: """Check:
1. NAMING:
   - Consistent snake_case for measurements/tables and fields
   - Descriptive names in English
   - Clear distinction between tags and fields

2. MODELING:
   - Tags for metadata that is indexed and queried (low cardinality)
   - Fields for measured values (high cardinality)
   - Appropriate time precision
   - No high-cardinality tags (kills performance)

3. PARTITIONING:
   - Time-based partitioning configured
   - Appropriate chunk/partition interval for data volume

4. RETENTION & AGGREGATION:
   - Retention policies for data lifecycle
   - Continuous aggregates for common query patterns
   - Appropriate downsampling strategy""",
    }

    def validate(self, config: DatabaseConfig, script: str) -> ValidationResult:
        practices = self._PRACTICES[config.db_type]

        system_prompt = f"""You are a {config.db_name} ({config.db_type.value} database) best practices expert.
Your task is to evaluate the script quality against best practices.

{practices}

NOTE: This is a schema-only script. Do NOT penalize for missing sample data.

Use the validate tool to return your assessment."""

        user_prompt = (
            f"Database: {config.db_name} {config.db_version}\n"
            f"Topic: {config.idea}\n\n"
            f"Script to evaluate:\n\n{script}"
        )

        result = self._validate_with_tool_use(system_prompt, user_prompt)
        logger.info("[%s] Result: %s — %s", self.name, result.status.value, result.feedback)
        return result
