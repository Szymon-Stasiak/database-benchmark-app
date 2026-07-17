from __future__ import annotations

from dbagnets.models.database_profile import DatabaseProfile
from dbagnets.models.enums import DatabaseType

_SYNTAX_CHECKS = """Check:
1. Whether each Cypher/graph statement has correct syntax (CREATE, MERGE, constraint definitions)
2. Whether node labels and relationship types follow valid naming rules
3. Whether property types and constraints are valid for {db_name} {db_version}
4. Whether constraint and index definitions reference existing labels/types
5. Whether the script is syntactically complete (no unclosed statements)
6. Whether relationship patterns are correctly formed (e.g. ()-[]->())
7. Whether advanced features use correct syntax: full-text index creation,
   existence constraints, node key constraints, composite indexes,
   point type usage, multiple labels per node"""

_VERSION_EXAMPLES = """Examples of version incompatibilities:
- Property existence constraints (IS NOT NULL) and node key constraints
  require Neo4j ENTERPRISE Edition — they FAIL on Community Edition.
  Flag any IS NOT NULL or NODE KEY constraint as FAIL.
- SHOW INDEXES (Neo4j < 4.2)
- Vector indexes (Neo4j < 5.11)
- Full-text indexes via db.index.fulltext.createNodeIndex (Neo4j < 4.0 uses different syntax)
- CREATE CONSTRAINT ... IS UNIQUE new syntax (Neo4j 5.x vs 4.x)
- Point type and spatial indexes (Neo4j < 3.4)
- Composite range indexes (Neo4j < 5.0)
- EXIST → IS NOT NULL constraint rename (Neo4j 5.0)"""

_BEST_PRACTICES = """Check:
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
   - FOREIGN KEY HANDLING (CRITICAL — do NOT flag as a problem):
     Every attribute defined in the LogicalSchema MUST exist as a node property
     with the EXACT same name — including FK-shaped attributes (e.g. director_id
     on Movie, user_id on Review, veterinarian_id on HealthRecord). FK columns
     are part of the schema contract; downstream tooling depends on 1:1 field
     coverage. A graph relationship (e.g. DIRECTED, WROTE) SHOULD additionally
     encode the same link, but it complements the FK property — it does not
     replace it. Do NOT request removal of any *_id property and do NOT FAIL
     the script for having FK properties on nodes.

3. INDEXES & CONSTRAINTS (random benchmark data is inserted later):
   - Unique constraints ONLY on primary-key properties.
     FAIL if uniqueness is enforced on any non-PK property (e.g. email, slug) —
     random data must not collide with value constraints.
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
   - Point/spatial types for geographic data if applicable"""

_COMPLIANCE_RULES = """DATABASE-SPECIFIC COMPLIANCE RULES (graph):
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
  these are Enterprise-only features and unavailable in Community Edition."""

_NAMING_RULES = """DATABASE-SPECIFIC NAMING RULES (graph):
- Each entity name in the LogicalSchema MUST appear as a node label.
  PascalCase conversion is ALLOWED (e.g. "movie_review" -> "MovieReview").
- GRAPH DATABASES ARE SCHEMA-LESS: properties are NOT declared in DDL.
  Properties only appear in the script when they are referenced by a constraint
  or an index. If a property has no constraint/index, it will NOT appear in the
  script at all — this is NORMAL and CORRECT. Do NOT flag missing properties
  that have no constraint or index.
- To verify attribute coverage, check ONLY:
  (a) Primary key attributes MUST appear in a uniqueness constraint on their node label.
  (b) Attributes marked as indexed or unique MUST appear in an index or constraint.
  (c) All other attributes (non-PK, non-indexed, non-unique) are NOT expected to
      appear in the DDL. Their absence is correct — they exist at data insertion time.
- FK attributes (another entity's ID stored on this node) MUST be ABSENT —
  the graph relationship encodes the link.
- Relationship type names MAY differ from LogicalSchema relationship names
  (UPPER_SNAKE_CASE is standard for graph DBs).
- Extra properties or constraints beyond the LogicalSchema are ALLOWED — do NOT penalize."""

_STRUCTURE_RULES = """- EVERY entity in the LogicalSchema MUST be a separate node label — do NOT
     collapse entities into relationship properties, even for leaf entities.
     This is required to preserve the relationship depth chain.
   - For EACH relationship in the LogicalSchema, the script MUST include either:
     (a) relationship property constraints (e.g. CREATE CONSTRAINT ... FOR ()-[r:REL_TYPE]-() ...)
     (b) relationship property indexes
     so that every relationship type is explicitly defined in the DDL.
   - FOREIGN KEY vs PRIMARY KEY distinction (CRITICAL):
     A PRIMARY KEY is an entity's OWN identifier on its OWN node
     (e.g. director_id on Director). PKs MUST be included as node properties.
     A FOREIGN KEY is ANOTHER entity's ID on THIS node (e.g. director_id on
     Movie, user_id on Review). FKs MUST be OMITTED — the relationship
     encodes the link. Only omit FK attributes, NEVER omit PKs.
   - Indexes on frequently queried properties (marked as indexed)
   - Uniqueness constraints ONLY for primary-key attributes
     (no uniqueness on natural identifiers like email/slug — random benchmark
      data must not collide with value constraints)
   - Use PascalCase for node labels and UPPER_SNAKE_CASE for relationship types
   NEO4J COMMUNITY EDITION LIMITATION (CRITICAL):
   - Property existence constraints (IS NOT NULL) and node key constraints
     require Neo4j Enterprise Edition. NEVER use them — they will cause runtime
     errors on Community Edition. Use uniqueness constraints and indexes only.
   PRODUCTION-SCALE DESIGN:
   - Range indexes on properties used in WHERE clauses and ORDER BY
   - Text indexes on properties used in full-text search (CONTAINS, STARTS WITH)
   - Composite indexes on properties frequently queried together
   MAXIMIZE NATIVE FEATURES — show what graphs do better than relational:
   - Rich relationship properties (e.g. role, weight, timestamp on edges)
     with indexes on those properties
   - Point/spatial types for geographic data
   - Additional traversal relationships beyond the LogicalSchema to expose
     useful graph navigation paths (e.g. shortcut relationships, reverse lookups)
   - Full-text indexes on text/string properties that users would search"""

GRAPH_PROFILE = DatabaseProfile(
    db_type=DatabaseType.GRAPH,
    syntax_checks=_SYNTAX_CHECKS,
    version_examples=_VERSION_EXAMPLES,
    best_practices=_BEST_PRACTICES,
    compliance_rules=_COMPLIANCE_RULES,
    naming_rules=_NAMING_RULES,
    structure_rules=_STRUCTURE_RULES,
)
