from __future__ import annotations

from dbagnets.agents.base import BaseAgent
from dbagnets.models.config import TargetConfig
from dbagnets.models.enums import DatabaseType
from dbagnets.models.schema import LogicalSchema
from dbagnets.models.type_mapping import format_type_mapping_prompt
from dbagnets.models.validation import ValidationResult


class SchemaComplianceCheckerAgent(BaseAgent):

    @property
    def name(self) -> str:
        return "SchemaComplianceChecker"

    @property
    def role_description(self) -> str:
        return "Verifies that a database script faithfully implements a LogicalSchema."

    _COMPLIANCE_RULES: dict[DatabaseType, str] = {
        DatabaseType.RELATIONAL: """DATABASE-SPECIFIC COMPLIANCE RULES (relational):
- Every entity MUST be a table.
- Every attribute MUST be a column with equivalent data type.
- 1:N relationships MUST use foreign keys.
- M:N relationships MUST use junction tables with foreign keys to both sides.
- All constraints (PK, UNIQUE, NOT NULL, indexes) MUST be preserved.
- Additional native features (views, triggers, computed columns, partitioning,
  CHECK constraints, ENUM types, sequences, domain types) are ALLOWED and
  encouraged — do NOT penalize for having extra structures beyond the LogicalSchema.""",

        DatabaseType.GRAPH: """DATABASE-SPECIFIC COMPLIANCE RULES (graph):
- Every entity MUST be represented as a node label.
  IMPORTANT: Even if a leaf entity seems like it could be a relationship property,
  it MUST still be a separate node label to preserve the schema's depth chain.
- Relationships from the LogicalSchema become relationship types between nodes.
- Additional traversal relationships beyond the LogicalSchema are ALLOWED and
  encouraged — they add graph expressiveness without violating compliance.
- Additional native features (full-text indexes, composite constraints, multiple
  labels, shortcut relationships, reverse lookups) are ALLOWED — do NOT penalize.
- FOREIGN KEY vs PRIMARY KEY distinction (CRITICAL):
  A PRIMARY KEY is an entity's OWN identifier on its OWN node
  (e.g. director_id on Director, movie_id on Movie, user_id on User).
  PKs MUST exist as node properties with uniqueness constraints.
  A FOREIGN KEY is ANOTHER entity's ID stored on THIS node
  (e.g. director_id on Movie, user_id on Review, movie_id on Review).
  FKs MUST be EXCLUDED from compliance checking — their absence is CORRECT.
  Do NOT require FK attributes as node properties. Do NOT require constraints
  on them. In graphs, relationships replace foreign keys entirely.
- Other non-FK attributes MUST be present as node properties.
- Indexed non-FK attributes MUST have indexes.
- Do NOT require IS NOT NULL (existence) constraints or node key constraints —
  these are Enterprise-only features and unavailable in Community Edition.""",

        DatabaseType.DOCUMENT: """DATABASE-SPECIFIC COMPLIANCE RULES (document):
- Entities can be implemented as standalone collections OR as embedded sub-documents
  within a parent collection. Both are valid — embedding is idiomatic and preferred
  when the entity is always accessed together with its parent.
- If an entity is embedded, its attributes MUST still be present as fields within
  the embedded document structure.
- Relationships can be implemented as reference fields (storing IDs), embedded
  documents, or arrays of embedded documents — all are acceptable.
- Data duplication across collections is ALLOWED and expected in document databases
  (denormalization is the norm, not a flaw).
- Constraints should be enforced via JSON Schema validation where possible.
- Additional native features (text indexes, TTL indexes, partial indexes, wildcard
  indexes, aggregation pipeline views, capped collections, comprehensive JSON Schema
  validation with enum/pattern/min/max) are ALLOWED — do NOT penalize.""",

        DatabaseType.VECTOR: """DATABASE-SPECIFIC COMPLIANCE RULES (vector):
- Every entity MUST be a collection.
- Vector attributes MUST use the native vector field type.
- Relationships are implemented via reference fields storing IDs.
- Additional native features (multiple vector fields, partition keys, dynamic
  schema, hybrid search config, index parameter tuning) are ALLOWED — do NOT penalize.""",

        DatabaseType.KEY_VALUE: """DATABASE-SPECIFIC COMPLIANCE RULES (key-value):
- Every entity MUST have a key namespace/pattern.
- Attributes are stored as hash fields or structured values.
- Relationships are encoded via key references.
- Additional native features (sorted sets for secondary indexes, streams for
  event data, HyperLogLog, geospatial commands, bitmaps, TTL policies, Lua scripts,
  pub/sub channels) are ALLOWED — do NOT penalize.""",

        DatabaseType.TIME_SERIES: """DATABASE-SPECIFIC COMPLIANCE RULES (time-series):
- Every entity MUST be a measurement/hypertable.
- Relationships are implemented via foreign keys or tag references.
- Time-based columns must use appropriate timestamp types.
- Additional native features (continuous aggregates, compression policies, retention
  policies, data tiering, downsampling, time_bucket functions) are ALLOWED — do NOT penalize.""",
    }

    def validate(
        self, target: TargetConfig, schema: LogicalSchema, script: str
    ) -> ValidationResult:
        type_hints = format_type_mapping_prompt(target.db_name)
        compliance_rules = self._COMPLIANCE_RULES[target.db_type]

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

        schema_json = schema.model_dump_json(indent=2)
        user_prompt = (
            f"LogicalSchema:\n{schema_json}\n\n"
            f"Database script for {target.db_name} {target.db_version}:\n\n{script}"
        )

        return self._validate_with_tool_use(system_prompt, user_prompt)
