from __future__ import annotations

from dbagnets.models.database_profile import DatabaseProfile
from dbagnets.models.enums import DatabaseType

_SYNTAX_CHECKS = """Check:
1. Whether each SQL statement has correct syntax (CREATE TABLE, CREATE INDEX, ALTER TABLE, etc.)
2. Whether all parentheses, quotes, and semicolons are properly closed
3. Whether data types are valid for {db_name} {db_version}
4. Whether SQL keywords are correct
5. Whether references to tables/columns in FOREIGN KEY are consistent
6. Whether statement ordering is correct (referenced tables must be created before referencing tables)
7. Whether advanced features use correct syntax: CREATE VIEW, CREATE TRIGGER,
   CREATE FUNCTION, GENERATED columns, CREATE SEQUENCE, partial indexes
8. NO TABLE PARTITIONING (FAIL the script if violated):
   The benchmark pipeline does not support partitioned tables — partitioning
   forces composite primary keys which break inserts and FK propagation.
   FAIL any CREATE TABLE statement that contains `PARTITION BY RANGE`,
   `PARTITION BY LIST`, `PARTITION BY HASH`, or `PARTITION BY KEY`. Tell the
   generator to remove the PARTITION BY clause and convert any composite
   primary key back to a single-column surrogate id."""

_VERSION_EXAMPLES = """Examples of version incompatibilities:
- GENERATED ALWAYS AS IDENTITY (PostgreSQL < 10)
- CREATE INDEX CONCURRENTLY with IF NOT EXISTS (PostgreSQL < 9.5)
- JSON_TABLE (MySQL < 8.0)
- ON CONFLICT DO UPDATE (PostgreSQL < 9.5)
- CREATE PROCEDURE (PostgreSQL < 11)
- GENERATED ALWAYS AS (stored/virtual) computed columns (PostgreSQL < 12, MySQL < 5.7)
- EXCLUSION constraints require btree_gist extension (PostgreSQL)
- CREATE OR REPLACE TRIGGER (PostgreSQL < 14)
- ENUM type variations differ between engines (PostgreSQL CREATE TYPE vs MySQL ENUM column)"""

_BEST_PRACTICES = """Check:
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

4. CONSTRAINTS (random benchmark data is inserted later — FAIL if violated):
   - NOT NULL used where appropriate
   - DEFAULT values make sense
   - PRIMARY KEY is the ONLY uniqueness allowed; PK is implicitly UNIQUE + NOT NULL
   - FAIL if script contains: UNIQUE (outside the PK), CHECK, EXCLUSION,
     CREATE TYPE ... AS ENUM, MySQL ENUM(...), CREATE DOMAIN

5. DATA TYPES:
   - Appropriate types (not VARCHAR(255) for everything)
   - Dates use proper types (TIMESTAMP, DATE)
   - Monetary values use DECIMAL/NUMERIC
   - Use plain VARCHAR/TEXT for fixed-set columns instead of ENUM types
   - UUID type for unique identifiers

6. PRODUCTION SCALE (FAIL if missing for high-volume entities):
   - NO TABLE PARTITIONING: FAIL the script if any table uses PARTITION BY
     (RANGE, LIST, HASH, KEY). Partitioning forces composite primary keys which
     break the benchmark's insert pipeline and break foreign keys from
     non-partitioned children. Tell the generator to remove all PARTITION BY
     clauses and use plain non-partitioned tables.
   - SINGLE-COLUMN PRIMARY KEYS: every table MUST have a single-column PK
     (a surrogate id). FAIL if any table uses a composite PRIMARY KEY
     (e.g. `PRIMARY KEY (id, other_col)`).
   - Covering indexes (INCLUDE) for the most common query patterns
   - Appropriate index types: B-tree for equality/range, GIN for arrays/JSONB/full-text
   - FILLFACTOR tuning on tables with frequent updates (via ALTER TABLE after
     creation).

7. NATIVE FEATURE UTILIZATION (PASS with suggestions if missing — do NOT FAIL):
   These are nice-to-have features. If 2+ are present, PASS. If none are present,
   include them as suggestions in feedback but still PASS:
   - GENERATED/computed columns for derived values (e.g. full_name from first+last)
   - Materialized views or views for common multi-table read patterns
   - Triggers for automatic audit fields (created_at, updated_at)
   - Sequences for controlled ID generation"""

_COMPLIANCE_RULES = """DATABASE-SPECIFIC COMPLIANCE RULES (relational):
- Every entity MUST be a table.
- Every attribute MUST be a column with equivalent data type.
- 1:N relationships MUST use foreign keys.
- M:N relationships MUST use junction tables with foreign keys to both sides.
- Structural constraints (PRIMARY KEY, FOREIGN KEY, NOT NULL, indexes) MUST be
  preserved. PRIMARY KEY is implicitly UNIQUE — do NOT require a separate UNIQUE.
- Every table MUST have a single-column primary key. Composite primary keys are
  forbidden (they break the benchmark insert pipeline).
- Table partitioning (PARTITION BY) is FORBIDDEN — it forces composite PKs.
  FAIL the script if any table uses PARTITION BY.
- Additional native features (views, triggers, computed columns, sequences)
  are ALLOWED and encouraged — do NOT penalize for having extra structures
  beyond the LogicalSchema.
- The script MUST NOT contain value-restricting constraints: UNIQUE outside the
  PK, CHECK, EXCLUSION, ENUM types, CREATE DOMAIN. Random benchmark data is
  inserted later and must not collide with value constraints."""

_NAMING_RULES = """DATABASE-SPECIFIC NAMING RULES (relational):
- Each entity name in the LogicalSchema MUST appear as a table name (exact match, snake_case).
- Each attribute name MUST appear as a column name in its corresponding table (exact match).
- Junction tables for M:N relationships MAY use a combined name (e.g. actors_movies)
  but the original entity tables MUST keep their schema names.
- Extra columns beyond the LogicalSchema (e.g. FK columns) are ALLOWED — do NOT penalize."""

_STRUCTURE_RULES = """- CREATE TABLE statements with columns and data types
   - Primary keys and foreign keys implementing all relationships
   - Indexes on all attributes marked as indexed in the schema
   - NOT NULL constraints as defined in the schema (no UNIQUE outside PK, no CHECK)
   - Junction tables for M:N relationships
   CONSTRAINT POLICY (CRITICAL — random benchmark data is inserted later):
   - ONLY structural constraints are allowed: PRIMARY KEY, FOREIGN KEY, NOT NULL.
   - Do NOT emit: UNIQUE (outside the PK itself), CHECK, EXCLUSION, ENUM types
     (CREATE TYPE ... AS ENUM, MySQL ENUM(...)), CREATE DOMAIN.
   - Value-restricting constraints break random-data benchmarks downstream.
   PRIMARY KEY RULE (CRITICAL — benchmark inserts depend on this):
   - Every table MUST have a SINGLE-COLUMN primary key (a surrogate id column).
   - NEVER use composite primary keys (e.g. `PRIMARY KEY (id, other_col)`).
   - Composite PKs break downstream insert benchmarks and FK propagation.
   NO TABLE PARTITIONING (CRITICAL):
   - Do NOT use `PARTITION BY RANGE`, `PARTITION BY LIST`, `PARTITION BY HASH`,
     or `PARTITION BY KEY` on any table.
   - Partitioning forces composite primary keys (PostgreSQL hard requirement)
     and breaks foreign keys from non-partitioned children. Skip it entirely.
   - For benchmark purposes, plain non-partitioned tables with proper indexes
     are sufficient and avoid FK conflicts.
   PRODUCTION-SCALE DESIGN (without partitioning):
   - Covering indexes (INCLUDE columns) for frequently queried combinations
   - Partial indexes on commonly filtered subsets (e.g. WHERE status = 'active')
   - Functional/expression indexes (e.g. LOWER(email)) for case-insensitive lookups
   - B-tree for equality/range, GIN for arrays/JSONB/full-text, GiST for spatial/ranges
   - FILLFACTOR tuning on tables with frequent updates
   MAXIMIZE NATIVE FEATURES — use as many of these as the schema allows:
   - GENERATED/computed columns for derived values
   - Materialized views or regular views for common multi-table read patterns
   - Triggers for audit timestamps (created_at, updated_at auto-population)
   - Sequences for controlled ID generation"""

RELATIONAL_PROFILE = DatabaseProfile(
    db_type=DatabaseType.RELATIONAL,
    syntax_checks=_SYNTAX_CHECKS,
    version_examples=_VERSION_EXAMPLES,
    best_practices=_BEST_PRACTICES,
    compliance_rules=_COMPLIANCE_RULES,
    naming_rules=_NAMING_RULES,
    structure_rules=_STRUCTURE_RULES,
)
