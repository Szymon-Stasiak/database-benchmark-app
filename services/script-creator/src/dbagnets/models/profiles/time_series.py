from __future__ import annotations

from dbagnets.models.database_profile import DatabaseProfile
from dbagnets.models.enums import DatabaseType

_SYNTAX_CHECKS = """Check:
1. Whether measurement/hypertable definitions have correct syntax for {db_name} {db_version}
2. Whether tag and field definitions use valid data types
3. Whether time-based partitioning configuration is syntactically correct
4. Whether retention policies use valid syntax
5. Whether continuous queries/aggregations are well-formed
6. Whether the script uses correct syntax for {db_name} (SQL, Flux, or InfluxQL)
7. Whether advanced features use correct syntax: continuous aggregate definitions,
   compression policies, retention policies, add_compression_policy/
   add_retention_policy, time_bucket functions, data tiering"""

_VERSION_EXAMPLES = """Examples of version incompatibilities:
- Continuous aggregates (TimescaleDB < 1.3)
- Compression (TimescaleDB < 1.5)
- Hierarchical continuous aggregates (TimescaleDB < 2.9)
- Flux language (InfluxDB < 2.0)
- Real-time continuous aggregates (TimescaleDB < 2.7)
- Compression with cagg (TimescaleDB < 2.6)
- Data tiering / tiered storage (TimescaleDB < 2.13)
- add_dimension for multi-dimensional partitioning (TimescaleDB)"""

_BEST_PRACTICES = """Check:
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
   - Downsampling continuous aggregates for long-term trend storage"""

_COMPLIANCE_RULES = """DATABASE-SPECIFIC COMPLIANCE RULES (time-series):
- Every entity MUST be a measurement/hypertable.
- Relationships are implemented via foreign keys or tag references.
- Time-based columns must use appropriate timestamp types.
- Additional native features (continuous aggregates, compression policies, retention
  policies, data tiering, downsampling, time_bucket functions) are ALLOWED — do NOT penalize."""

_NAMING_RULES = """DATABASE-SPECIFIC NAMING RULES (time-series):
- Each entity name MUST appear as a table/hypertable/measurement name
  (exact match, snake_case).
- Each attribute MUST appear as a column/tag/field name (exact match, snake_case).
- Extra columns beyond the LogicalSchema are ALLOWED — do NOT penalize."""

_STRUCTURE_RULES = """- Measurement/hypertable definitions with tags and fields
   - Time-based partitioning configuration
   - Relationship implementation via foreign keys or tag references
   - Indexes on indexed attributes
   PRODUCTION-SCALE DESIGN:
   - Chunk interval tuned to data_size_hints: 1 day for high-frequency, 1 week for low-frequency
   - Compression policies with segmentby (tags) and orderby (time) for 90%+ compression
   - Retention policies to automatically drop data older than business requirements
   - Composite indexes on (time, tag columns) for the primary query pattern: time-range + filter
   MAXIMIZE NATIVE FEATURES — show what time-series DBs do better:
   - Continuous aggregates for pre-computed rollups (hourly, daily, weekly summaries)
   - Real-time aggregation functions (time_bucket, first, last, interpolate)
   - Hierarchical continuous aggregates (minute → hour → day)
   - Data tiering policies (move old data to cheaper storage)
   - Downsampling continuous aggregates for long-term trend storage
   - Space-partitioning with add_dimension for multi-tenant or multi-device data"""

TIME_SERIES_PROFILE = DatabaseProfile(
    db_type=DatabaseType.TIME_SERIES,
    syntax_checks=_SYNTAX_CHECKS,
    version_examples=_VERSION_EXAMPLES,
    best_practices=_BEST_PRACTICES,
    compliance_rules=_COMPLIANCE_RULES,
    naming_rules=_NAMING_RULES,
    structure_rules=_STRUCTURE_RULES,
)
