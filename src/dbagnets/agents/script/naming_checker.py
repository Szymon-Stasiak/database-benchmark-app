from __future__ import annotations

from dbagnets.agents.base import BaseAgent
from dbagnets.models.config import TargetConfig
from dbagnets.models.enums import DatabaseType
from dbagnets.models.schema import LogicalSchema
from dbagnets.models.validation import ValidationResult


class NamingConsistencyCheckerAgent(BaseAgent):

    @property
    def name(self) -> str:
        return "NamingConsistencyChecker"

    @property
    def role_description(self) -> str:
        return "Verifies that entity and attribute names in a database script match the LogicalSchema exactly."

    _NAMING_RULES: dict[DatabaseType, str] = {
        DatabaseType.RELATIONAL: """DATABASE-SPECIFIC NAMING RULES (relational):
- Each entity name in the LogicalSchema MUST appear as a table name (exact match, snake_case).
- Each attribute name MUST appear as a column name in its corresponding table (exact match).
- Junction tables for M:N relationships MAY use a combined name (e.g. actors_movies)
  but the original entity tables MUST keep their schema names.
- Extra columns beyond the LogicalSchema (e.g. FK columns) are ALLOWED — do NOT penalize.""",

        DatabaseType.GRAPH: """DATABASE-SPECIFIC NAMING RULES (graph):
- Each entity name in the LogicalSchema MUST appear as a node label.
  PascalCase conversion is ALLOWED (e.g. "movie_review" -> "MovieReview").
- Each non-FK attribute MUST appear as a property name on its node (exact match, snake_case).
  FK attributes (another entity's ID stored on this node) are EXCLUDED — their absence is correct.
- Relationship type names MAY differ from LogicalSchema relationship names
  (UPPER_SNAKE_CASE is standard for graph DBs).
- Extra properties beyond the LogicalSchema are ALLOWED — do NOT penalize.""",

        DatabaseType.DOCUMENT: """DATABASE-SPECIFIC NAMING RULES (document):
- Each entity name MUST appear either as a collection name OR as an embedded
  sub-document key (exact match, snake_case).
- Each attribute MUST appear as a field name in its collection or embedded document
  (exact match, snake_case).
- Denormalized/snapshot fields are ALLOWED — do NOT penalize extra fields.""",

        DatabaseType.VECTOR: """DATABASE-SPECIFIC NAMING RULES (vector):
- Each entity name MUST appear as a collection name (exact match, snake_case).
- Each attribute MUST appear as a field name in its collection (exact match, snake_case).
- Extra fields beyond the LogicalSchema are ALLOWED — do NOT penalize.""",

        DatabaseType.KEY_VALUE: """DATABASE-SPECIFIC NAMING RULES (key-value):
- Each entity name MUST appear in the key namespace/pattern (exact match, snake_case).
- Each attribute MUST appear as a hash field name or structured value key
  (exact match, snake_case).
- Extra fields beyond the LogicalSchema are ALLOWED — do NOT penalize.""",

        DatabaseType.TIME_SERIES: """DATABASE-SPECIFIC NAMING RULES (time-series):
- Each entity name MUST appear as a table/hypertable/measurement name
  (exact match, snake_case).
- Each attribute MUST appear as a column/tag/field name (exact match, snake_case).
- Extra columns beyond the LogicalSchema are ALLOWED — do NOT penalize.""",
    }

    def validate(
        self, target: TargetConfig, schema: LogicalSchema, script: str
    ) -> ValidationResult:
        naming_rules = self._NAMING_RULES[target.db_type]

        entity_list = []
        for entity in schema.entities:
            attr_names = [a.name for a in entity.attributes]
            entity_list.append(
                f"  - Entity '{entity.name}': attributes = {attr_names}"
            )
        entity_checklist = "\n".join(entity_list)

        system_prompt = f"""You are a database naming consistency expert.
Your task is to verify that a {target.db_name} ({target.db_type.value}) script
uses EXACTLY the same entity names and attribute names as the LogicalSchema.

{naming_rules}

PROCEDURE — check each item below:

1. ENTITY NAME MATCHING:
   For each entity listed below, verify that a structure with that EXACT name
   exists in the script (table, node label, collection, etc.):
{entity_checklist}

2. ATTRIBUTE NAME MATCHING:
   For each entity, verify that EVERY attribute from the list above exists
   in the script with that EXACT name (column, property, field, etc.).
   Count the attributes: the script must have AT LEAST as many as the schema
   (extra attributes like FK columns are allowed).

3. ATTRIBUTE COUNT:
   For each entity, count the schema attributes and the script attributes.
   The script MUST have >= the number of schema attributes for that entity.

FAIL if ANY entity name is missing, renamed, or misspelled.
FAIL if ANY attribute name is missing, renamed, or misspelled.
In your feedback, list every mismatch as: "Entity 'X': expected attribute 'Y', found 'Z'" or "Entity 'X' missing from script".

Use the validate tool to return your assessment."""

        schema_json = schema.model_dump_json(indent=2)
        user_prompt = (
            f"LogicalSchema:\n{schema_json}\n\n"
            f"Database script for {target.db_name} {target.db_version}:\n\n{script}"
        )

        return self._validate_with_tool_use(system_prompt, user_prompt)
