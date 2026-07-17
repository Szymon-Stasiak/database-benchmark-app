from __future__ import annotations

from dbagnets.models.database_profile import DatabaseProfile
from dbagnets.models.enums import DatabaseType

_SYNTAX_CHECKS = """Check:
1. Whether key/keyspace definitions have correct syntax for {db_name} {db_version}
2. Whether data structure definitions are valid
3. Whether TTL/expiration configurations are syntactically correct
4. Whether index definitions (if applicable) use valid syntax
5. Whether the script uses correct commands/syntax for {db_name}
6. Whether key naming patterns are consistent
7. Whether advanced features use correct syntax: XADD/stream commands,
   PFADD/HyperLogLog, GEOADD/geospatial, SETBIT/bitmaps, sorted set
   operations, EVAL/Lua scripts, SUBSCRIBE/pub-sub"""

_VERSION_EXAMPLES = """Examples of version incompatibilities:
- Streams (Redis < 5.0)
- ACL (Redis < 6.0)
- JSON module (Redis < 6.2 without RedisJSON)
- Functions (Redis < 7.0)
- GETDEL command (Redis < 6.2)
- OBJECT FREQ (Redis < 4.0)
- Client-side caching (Redis < 6.0)
- Sharded pub/sub (Redis < 7.0)"""

_BEST_PRACTICES = """Check:
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
   - Pub/Sub channels for entity change notifications"""

_COMPLIANCE_RULES = """DATABASE-SPECIFIC COMPLIANCE RULES (key-value):
- Every entity MUST have a key namespace/pattern.
- Attributes are stored as hash fields or structured values.
- Relationships are encoded via key references.
- Additional native features (sorted sets for secondary indexes, streams for
  event data, HyperLogLog, geospatial commands, bitmaps, TTL policies, Lua scripts,
  pub/sub channels) are ALLOWED — do NOT penalize."""

_NAMING_RULES = """DATABASE-SPECIFIC NAMING RULES (key-value):
- Each entity name MUST appear in the key namespace/pattern (exact match, snake_case).
- Each attribute MUST appear as a hash field name or structured value key
  (exact match, snake_case).
- Extra fields beyond the LogicalSchema are ALLOWED — do NOT penalize."""

_STRUCTURE_RULES = """- Key namespace/keyspace definitions for each entity
   - Data structure definitions (hashes, lists, sets, sorted sets)
   - Key reference patterns implementing relationships
   - Secondary index definitions for indexed attributes
   PRODUCTION-SCALE DESIGN:
   - Memory-efficient encoding: use hashes for objects (ziplist encoding for small hashes)
   - Key expiration strategies: volatile-lru, allkeys-lfu depending on use case
   - Pipeline-friendly key design: namespace:entity_id pattern for batch operations
   - Secondary indexes via sorted sets for non-primary lookups at scale
   MAXIMIZE NATIVE FEATURES — show what key-value stores do better:
   - Hashes for entity storage (HSET/HGET for field-level access)
   - Sorted sets for ranked data, leaderboards, time-ordered indexes
   - Sets for unique collections, tags, M:N relationship members
   - Streams with consumer groups for event/activity log entities
   - HyperLogLog for approximate cardinality counting (unique visitors, etc.)
   - Geospatial commands (GEOADD/GEOSEARCH) for location-based entities
   - TTL policies (EXPIRE) on ephemeral/session-like data
   - Bitmaps for boolean flag matrices (feature flags, permissions)
   - Lua scripts for atomic multi-key operations
   - Pub/Sub channels for real-time entity change notifications"""

KEY_VALUE_PROFILE = DatabaseProfile(
    db_type=DatabaseType.KEY_VALUE,
    syntax_checks=_SYNTAX_CHECKS,
    version_examples=_VERSION_EXAMPLES,
    best_practices=_BEST_PRACTICES,
    compliance_rules=_COMPLIANCE_RULES,
    naming_rules=_NAMING_RULES,
    structure_rules=_STRUCTURE_RULES,
)
