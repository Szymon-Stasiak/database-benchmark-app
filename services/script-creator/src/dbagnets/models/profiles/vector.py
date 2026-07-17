from __future__ import annotations

from dbagnets.models.database_profile import DatabaseProfile
from dbagnets.models.enums import DatabaseType

_SYNTAX_CHECKS = """Check:
1. Whether collection creation statements have correct syntax for {db_name} {db_version}
2. Whether field definitions are valid (field names, data types, dimensions)
3. Whether vector index parameters are valid (index type, metric type)
4. Whether all referenced collections/fields exist
5. Whether the script uses correct API/DDL syntax for {db_name}
6. Whether required parameters (dimensions, metric type) are specified
7. Whether advanced features use correct syntax: partition key definitions,
   multiple vector fields, dynamic schema config, index parameter tuning
   (nlist, M, efConstruction)"""

_VERSION_EXAMPLES = """Examples of version incompatibilities:
- GPU index support (Milvus < 2.3)
- JSON field type (Milvus < 2.2)
- Dynamic schema (Milvus < 2.2)
- Range search (Milvus < 2.3)
- Multiple vector fields per collection (Milvus < 2.4)
- Partition key (Milvus < 2.2.9)
- ScaNN index type (Milvus < 2.4)
- Upsert operations (Milvus < 2.3)"""

_BEST_PRACTICES = """Check:
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
   - Consistency level configuration for read/write tradeoffs"""

_COMPLIANCE_RULES = """DATABASE-SPECIFIC COMPLIANCE RULES (vector):
- Every entity MUST be a collection.
- Vector attributes MUST use the native vector field type.
- Relationships are implemented via reference fields storing IDs.
- Additional native features (multiple vector fields, partition keys, dynamic
  schema, hybrid search config, index parameter tuning) are ALLOWED — do NOT penalize."""

_NAMING_RULES = """DATABASE-SPECIFIC NAMING RULES (vector):
- Each entity name MUST appear as a collection name (exact match, snake_case).
- Each attribute MUST appear as a field name in its collection (exact match, snake_case).
- Extra fields beyond the LogicalSchema are ALLOWED — do NOT penalize."""

_STRUCTURE_RULES = """- Collection definitions with scalar and vector fields
   - Vector index configuration (index type, metric type, dimensions)
   - Primary key / ID field for each collection
   - Reference fields implementing relationships between collections
   PRODUCTION-SCALE DESIGN:
   - Choose index type based on data_size_hints: IVF_FLAT (<100K rows), HNSW (<10M, latency-critical),
     IVF_SQ8/IVF_PQ (>10M rows, memory-constrained) — match the scale
   - Tune index params: HNSW (M=16-64, efConstruction=200-500), IVF (nlist=sqrt(N))
   - Partition keys for data distribution on collections with >1M rows
   - Scalar indexes on ALL filter fields — hybrid search (vector + filter) is the primary use case
   MAXIMIZE NATIVE FEATURES — show what vector DBs do better:
   - Multiple vector fields per collection when entity has different embeddings
     AND the target database version supports it (see VERSION CONSTRAINTS below).
     If multi-vector is unsupported, choose the single most important embedding
     and store the others in a separate collection.
   - Correct metric type per use case (COSINE for text, L2 for images, IP for recommendations)
   - Dynamic schema fields where additional metadata varies per record
   - Consistency level configuration for read/write tradeoffs"""

VECTOR_PROFILE = DatabaseProfile(
    db_type=DatabaseType.VECTOR,
    syntax_checks=_SYNTAX_CHECKS,
    version_examples=_VERSION_EXAMPLES,
    best_practices=_BEST_PRACTICES,
    compliance_rules=_COMPLIANCE_RULES,
    naming_rules=_NAMING_RULES,
    structure_rules=_STRUCTURE_RULES,
)
