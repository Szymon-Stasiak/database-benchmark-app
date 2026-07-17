from __future__ import annotations

from dbagnets.models.database_profile import DatabaseProfile
from dbagnets.models.enums import DatabaseType

_SYNTAX_CHECKS = """Check:
1. Whether collection/bucket creation statements have correct syntax for {db_name} {db_version}
2. Whether JSON Schema validation rules are well-formed (valid $jsonSchema)
3. Whether index definitions reference valid fields
4. Whether document references (DBRef or manual) are consistent
5. Whether the script uses correct syntax for {db_name}
6. Whether all required fields in schema definitions are properly typed
7. Whether advanced features use correct syntax: text indexes, TTL indexes,
   partial/sparse indexes, wildcard indexes, compound indexes, collation
   options, capped collection parameters, aggregation pipeline views,
   JSON Schema bsonType / required (type-only validators)"""

_VERSION_EXAMPLES = """Examples of version incompatibilities:
- JSON Schema validation (MongoDB < 3.6)
- $merge aggregation stage (MongoDB < 4.2)
- Wildcard indexes (MongoDB < 4.2)
- Time series collections (MongoDB < 5.0)
- Clustered indexes on collections (MongoDB < 5.3)
- $densify and $fill operators (MongoDB < 5.3)
- Queryable encryption (MongoDB < 7.0)
- Compound wildcard indexes (MongoDB < 7.0)
- Partial indexes (MongoDB < 3.2)
- Collation support (MongoDB < 3.4)"""

_BEST_PRACTICES = """Check:
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

4. SCHEMA VALIDATION (random benchmark data is inserted later — FAIL if violated):
   - JSON Schema validation is OPTIONAL and, if present, MUST be type-only
     (bsonType / required). FAIL if validators use: enum, pattern, minimum,
     maximum, minLength, maxLength — random data must not collide with value
     constraints.

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
   - Capped collections for fixed-size log-like entities"""

_COMPLIANCE_RULES = """DATABASE-SPECIFIC COMPLIANCE RULES (document):
- Entities can be implemented as standalone collections OR as embedded sub-documents
  within a parent collection. Both are valid — embedding is idiomatic and preferred
  when the entity is always accessed together with its parent.
- If an entity is embedded, its attributes MUST still be present as fields within
  the embedded document structure.
- Relationships can be implemented as reference fields (storing IDs), embedded
  documents, or arrays of embedded documents — all are acceptable.
- Data duplication across collections is ALLOWED and expected in document databases
  (denormalization is the norm, not a flaw).
- JSON Schema validation is OPTIONAL; if present it MUST be type-only
  (bsonType / required). The script MUST NOT use value validators: enum, pattern,
  minimum, maximum, minLength, maxLength — random benchmark data is inserted later
  and must not collide with value constraints.
- Additional native features (text indexes, TTL indexes, partial indexes, wildcard
  indexes, aggregation pipeline views, capped collections) are ALLOWED — do NOT
  penalize."""

_NAMING_RULES = """DATABASE-SPECIFIC NAMING RULES (document):
- Each entity name MUST appear either as a collection name OR as an embedded
  sub-document key (exact match, snake_case).
- Each attribute MUST appear as a field name in its collection or embedded document
  (exact match, snake_case).
- Denormalized/snapshot fields are ALLOWED — do NOT penalize extra fields."""

_STRUCTURE_RULES = """- Collection definitions for top-level entities
   - Index definitions on indexed attributes
   - Relationships via embedded sub-documents (preferred for data accessed together)
     or reference fields (for independently queried entities)
   - Denormalization is expected — embed related data for read performance
   CONSTRAINT POLICY (CRITICAL — random benchmark data is inserted later):
   - JSON Schema validation is OPTIONAL and, if present, MUST be type-only
     (bsonType / required). Do NOT use enum, pattern, minimum, maximum,
     minLength, maxLength — value validators break random-data benchmarks.
   PRODUCTION-SCALE DESIGN:
   - Compound indexes following ESR rule (Equality, Sort, Range) for query optimization
   - Covered queries: include all projected fields in indexes where possible
   - Partial/sparse indexes for optional fields to save space at scale
   - Shard key design: choose high-cardinality fields for even data distribution
   - Read concern/write concern configuration for consistency vs performance tradeoffs
   MAXIMIZE NATIVE FEATURES — show what document DBs do better:
   - Multi-key indexes on array fields (e.g. tags, categories)
   - Text indexes for full-text search on string/text fields
   - TTL indexes on timestamp fields for data with natural expiration
   - Wildcard indexes on polymorphic or flexible sub-documents
   - Collation-aware indexes for locale-specific string sorting
   - Aggregation pipeline views for common read patterns
   - Change streams configuration for real-time data processing
   - Capped collections for fixed-size log-like entities"""

DOCUMENT_PROFILE = DatabaseProfile(
    db_type=DatabaseType.DOCUMENT,
    syntax_checks=_SYNTAX_CHECKS,
    version_examples=_VERSION_EXAMPLES,
    best_practices=_BEST_PRACTICES,
    compliance_rules=_COMPLIANCE_RULES,
    naming_rules=_NAMING_RULES,
    structure_rules=_STRUCTURE_RULES,
)
