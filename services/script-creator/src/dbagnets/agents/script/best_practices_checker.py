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
   - Partial indexes on commonly filtered subsets
   - Functional/expression indexes where queries would benefit
   - No excessive indexing

4. CONSTRAINTS:
   - NOT NULL used where appropriate
   - UNIQUE used where needed
   - CHECK constraints with meaningful domain validation (value ranges, patterns)
   - DEFAULT values make sense
   - EXCLUSION constraints for non-overlapping ranges where applicable

5. DATA TYPES:
   - Appropriate types (not VARCHAR(255) for everything)
   - Dates use proper types (TIMESTAMP, DATE)
   - Monetary values use DECIMAL/NUMERIC
   - ENUM types for fixed-set columns
   - UUID type for unique identifiers

6. PRODUCTION SCALE (FAIL if missing for high-volume entities):
   - Table partitioning for entities with high data_size_hints (>100K rows)
     PARTITIONING + PK RULE (PostgreSQL hard requirement): on a partitioned table the
     PRIMARY KEY and every UNIQUE constraint MUST include all partition-key columns.
     A single-column PRIMARY KEY combined with PARTITION BY on a different column is
     ILLEGAL and will fail at init with "unique constraint on partitioned table must
     include all partitioning columns". FAIL the script in this case and tell the
     generator to use a composite PRIMARY KEY (e.g. PRIMARY KEY (id, created_at))
     plus matching composite FKs (or drop the FK to that partitioned parent if the
     partition column can't be propagated). Same rule for UNIQUE constraints: every
     UNIQUE on a partitioned table must contain every partition column.
     Also: FILLFACTOR must NOT appear in the same CREATE TABLE as PARTITION BY —
     it should be applied via ALTER TABLE afterward. Do NOT fail for this ordering.
   - Covering indexes (INCLUDE) for the most common query patterns
   - Appropriate index types: B-tree for equality/range, GIN for arrays/JSONB/full-text
   - FILLFACTOR tuning on tables with frequent updates (via ALTER TABLE if partitioned)

7. NATIVE FEATURE UTILIZATION (PASS with suggestions if missing — do NOT FAIL):
   These are nice-to-have features. If 2+ are present, PASS. If none are present,
   include them as suggestions in feedback but still PASS:
   - GENERATED/computed columns for derived values (e.g. full_name from first+last)
   - Materialized views or views for common multi-table read patterns
   - Triggers for automatic audit fields (created_at, updated_at)
   - Sequences for controlled ID generation
   - Domain types for reusable type+constraint bundles""",

        DatabaseType.GRAPH: """Check:
1. NAMING:
   - Node labels use PascalCase (e.g. Movie, Actor)
   - Relationship types use UPPER_SNAKE_CASE (e.g. ACTED_IN, DIRECTED)
   - Properties use camelCase or snake_case consistently
   - Descriptive names in English

2. MODELING:
   - Every entity from the LogicalSchema MUST remain as its own separate node label.
     Do NOT suggest merging, collapsing, or combining entities — even if they share
     attributes (e.g. Actor and Director both having name fields). The LogicalSchema
     is the source of truth and cannot be changed. This is non-negotiable.
   - Properties are on the correct entity (node vs relationship)
   - Rich relationship properties where applicable (role, weight, timestamp on edges)
   - FOREIGN KEY vs PRIMARY KEY distinction (CRITICAL):
     A PRIMARY KEY is an entity's OWN identifier on its OWN node
     (e.g. director_id on Director, movie_id on Movie). PKs MUST be kept.
     A FOREIGN KEY is ANOTHER entity's ID stored on THIS node to encode
     a reference (e.g. director_id on Movie, user_id on Review).
     FKs MUST NOT appear as node properties — the graph relationship
     encodes the link. Only remove FK attributes, NEVER remove PKs.

3. INDEXES & CONSTRAINTS:
   - Unique constraints on natural identifiers
   - Indexes on frequently queried properties
   - Range indexes vs text indexes — correct index type per property data type
   - Composite indexes on frequently co-queried properties
   - Do NOT use existence constraints (IS NOT NULL) or node key constraints —
     these require Neo4j Enterprise Edition and FAIL on Community Edition.

4. PROPERTIES:
   - Appropriate property types (not strings for everything)
   - Temporal properties use proper types (datetime, date)
   - Point/spatial types for geographic data

5. PRODUCTION SCALE (FAIL if missing for high-volume entities):
   - Composite indexes on properties frequently queried together
   - Range indexes on all properties used in WHERE clauses and ORDER BY
   - Text indexes on all string properties used in CONTAINS / STARTS WITH queries

6. NATIVE FEATURE UTILIZATION (PASS with suggestions if missing — do NOT FAIL):
   These are nice-to-have features. If 2+ are present, PASS. If none are present,
   include them as suggestions in feedback but still PASS:
   - Full-text indexes on text/string properties that users would search
   - Relationship properties with constraints (existence, type)
   - Rich relationship properties (role, weight, timestamp on edges)
   - Point/spatial types for geographic data if applicable""",

        DatabaseType.VECTOR: """Check:
1. NAMING:
   - Consistent snake_case for collection and field names
   - Descriptive names in English
   - Clear distinction between scalar and vector fields

2. VECTOR DESIGN:
   - Appropriate vector dimensions for the use case
   - Correct metric type for the data (COSINE for text, L2 for images, IP for recs)
   - Suitable index type for the scale (IVF_FLAT small, HNSW latency, IVF_PQ large)

3. SCHEMA:
   - Primary key field defined
   - Partition key chosen for data distribution on large collections
   - Scalar fields for filtering are properly typed

4. INDEXES:
   - Vector indexes configured with appropriate parameters (nlist, M, efConstruction)
   - Scalar indexes on ALL filter fields for efficient hybrid search

5. PRODUCTION SCALE (FAIL if missing for high-volume collections):
   - Index type matched to data volume: IVF_FLAT (<100K), HNSW (<10M, latency-critical),
     IVF_SQ8/IVF_PQ (>10M, memory-constrained)
   - Index parameters tuned for scale: HNSW (M=16-64, efConstruction=200-500),
     IVF (nlist=sqrt(N))
   - Partition keys for data distribution on collections with >1M rows
   - Scalar indexes on ALL filter fields for efficient hybrid search

6. NATIVE FEATURE UTILIZATION (PASS with suggestions if missing — do NOT FAIL):
   These are nice-to-have features. If 2+ are present, PASS. If none are present,
   include them as suggestions in feedback but still PASS:
   - Multiple vector fields when entity has different embedding types
   - Correct metric type per use case (COSINE for text, L2 for images, IP for recs)
   - Dynamic schema fields for variable metadata
   - Hybrid search configuration (vector + scalar filtering)
   - Consistency level configuration for read/write tradeoffs""",

        DatabaseType.DOCUMENT: """Check:
1. NAMING:
   - Consistent snake_case for collection and field names
   - Descriptive names in English
   - Clear naming for reference fields

2. MODELING:
   - DENORMALIZATION IS EXPECTED and encouraged in document databases.
     Embedding related data within documents is the primary pattern — do NOT
     penalize data duplication across collections.
   - Frequently accessed together data SHOULD be embedded.
   - Some entities from the LogicalSchema MAY be embedded as sub-documents
     within parent collections instead of being separate collections — this
     is idiomatic and preferred when the data is always accessed together.
   - References (storing IDs) are acceptable for large or independently
     queried entities, but embedding is the default choice.
   - Avoid excessive nesting depth (>3 levels) in embedded documents.

3. INDEXES:
   - Indexes on frequently queried fields
   - Compound indexes for common query patterns (follow ESR rule)
   - Multi-key indexes on array fields (tags, categories, etc.)
   - No excessive indexing

4. SCHEMA VALIDATION:
   - Required fields are enforced
   - Field types are specified
   - Enum values are constrained where appropriate
   - Pattern validation on formatted strings (email, URL, etc.)
   - Min/max constraints on numeric fields where applicable

5. PRODUCTION SCALE (FAIL if missing for high-volume collections):
   - Compound indexes following ESR rule (Equality, Sort, Range) for query optimization
   - Covered queries: include all projected fields in indexes where possible
   - Partial/sparse indexes for optional fields to save space at scale
   - Shard key design: high-cardinality fields for even data distribution
   - Read concern / write concern configuration for consistency vs performance

6. NATIVE FEATURE UTILIZATION (PASS with suggestions if missing — do NOT FAIL):
   These are nice-to-have features. If 3+ are present, PASS. If fewer are present,
   include them as suggestions in feedback but still PASS:
   - Text indexes for full-text search on string/text fields
   - TTL indexes on timestamp fields for data with natural expiration
   - Wildcard indexes on polymorphic or flexible sub-documents
   - Collation-aware indexes for locale-specific sorting
   - Aggregation pipeline views for common read patterns
   - Capped collections for fixed-size log-like entities
   - Comprehensive JSON Schema validation (not just types — use enum, pattern,
     minimum, maximum, minLength, maxLength where domain rules apply)""",

        DatabaseType.KEY_VALUE: """Check:
1. NAMING:
   - Consistent key naming convention (e.g. entity:id:field)
   - Descriptive names in English
   - Namespace separation between entity types

2. DATA STRUCTURES:
   - Appropriate structure choice (hash, list, set, sorted set, stream)
   - Hashes for object-like data
   - Sets/sorted sets for relationships and indexes
   - Lists for ordered sequences

3. DESIGN:
   - Secondary indexes implemented where needed
   - TTL policies on ephemeral data
   - Key references are consistent

4. EFFICIENCY:
   - No overly large keys or values
   - Appropriate granularity (not too fine, not too coarse)

5. PRODUCTION SCALE (FAIL if missing for high-volume keyspaces):
   - Memory-efficient encoding: hashes with ziplist for small objects
   - Key expiration strategies: volatile-lru, allkeys-lfu depending on use case
   - Pipeline-friendly key design: namespace:entity_id pattern for batch operations
   - Secondary indexes via sorted sets for non-primary lookups at scale
   - Key partitioning strategy for cluster-mode distribution

6. NATIVE FEATURE UTILIZATION (PASS with suggestions if missing — do NOT FAIL):
   These are nice-to-have features. If 3+ are present, PASS. If fewer are present,
   include them as suggestions in feedback but still PASS:
   - Sorted sets for ranked/scored data (leaderboards, ratings, time-ordered feeds)
   - Streams with consumer groups for event/activity log entities
   - HyperLogLog for approximate cardinality counting (unique visitors, etc.)
   - Sets for unique membership collections (tags, M:N members)
   - Geospatial commands (GEOADD/GEOSEARCH) for location-based entities
   - Bitmaps for boolean flag matrices (feature flags, permissions)
   - TTL (EXPIRE) on session-like or ephemeral data
   - Lua scripts for atomic multi-key operations
   - Pub/Sub channels for entity change notifications""",

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
   - Appropriate downsampling strategy

5. PRODUCTION SCALE (FAIL if missing for high-volume hypertables):
   - Chunk interval tuned to data volume: 1 day for high-frequency, 1 week for low-frequency
   - Compression policies with segmentby (tags) and orderby (time) for 90%+ compression
   - Retention policies to automatically drop data older than business requirements
   - Composite indexes on (time, tag columns) for the primary query pattern
   - Space-partitioning with add_dimension for multi-tenant or multi-device data

6. NATIVE FEATURE UTILIZATION (PASS with suggestions if missing — do NOT FAIL):
   These are nice-to-have features. If 2+ are present, PASS. If fewer are present,
   include them as suggestions in feedback but still PASS:
   - Continuous aggregates for pre-computed rollups (hourly, daily summaries)
   - Hierarchical continuous aggregates (minute → hour → day)
   - Real-time aggregation functions (time_bucket, first, last, interpolate)
   - Data tiering policies for moving old data to cheaper storage
   - Downsampling continuous aggregates for long-term trend storage""",
    }

    def validate(self, config: DatabaseConfig, script: str) -> ValidationResult:
        practices = self._PRACTICES[config.db_type]

        system_prompt = f"""You are a senior {config.db_name} ({config.db_type.value} database) production architect.
Your task is to evaluate whether this script is PRODUCTION-READY for a database
that will hold millions of rows and serve as a benchmark to showcase {config.db_name}'s strengths.

{practices}

IMPORTANT CONSTRAINTS:
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
            f"Database: {config.db_name} {config.db_version}\n"
            f"Topic: {config.idea}\n\n"
            f"Script to evaluate:\n\n{script}"
        )

        result = self._validate_with_tool_use(system_prompt, user_prompt)
        logger.info("[%s] Result: %s — %s", self.name, result.status.value, result.feedback)
        return result
