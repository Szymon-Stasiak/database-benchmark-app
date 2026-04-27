from __future__ import annotations

import logging

from dbagnets.agents.base import BaseAgent
from dbagnets.models import DatabaseConfig, DatabaseType, ValidationResult

logger = logging.getLogger("dbagnets")


class DepthCheckerAgent(BaseAgent):

    @property
    def name(self) -> str:
        return "DepthChecker"

    @property
    def role_description(self) -> str:
        return "Checks whether the relationship depth matches the requirements."

    _DEPTH_DEFINITION: dict[DatabaseType, str] = {
        DatabaseType.RELATIONAL: """DEFINITION OF RELATIONSHIP DEPTH:
Relationship depth = the longest chain of FOREIGN KEY relationships
from any root table to a leaf table.

Examples:
- depth=1: Table_A -> Table_B (1 FK, 2 tables)
- depth=2: Table_A -> Table_B -> Table_C (2 FKs, 3 tables)
- depth=3: Table_A -> Table_B -> Table_C -> Table_D (3 FKs, 4 tables)

Junction/pivot tables in M:N relationships DO COUNT as a level.

Your task is to:
1. Identify ALL tables and their FOREIGN KEY relationships
2. Map the relationship graph
3. Find the longest path
4. Check whether the longest path has exactly {depth} levels""",

        DatabaseType.GRAPH: """DEFINITION OF RELATIONSHIP DEPTH:
Relationship depth = the longest chain of distinct relationship types
connecting node labels in the schema.

Examples:
- depth=1: (:A)-[:R1]->(:B) (1 relationship type, 2 node labels)
- depth=2: (:A)-[:R1]->(:B)-[:R2]->(:C) (2 relationship types, 3 node labels)
- depth=3: (:A)-[:R1]->(:B)-[:R2]->(:C)-[:R3]->(:D) (3 relationship types, 4 node labels)

Your task is to:
1. Identify ALL node labels and relationship types
2. Map the relationship graph between labels
3. Find the longest path
4. Check whether the longest path has exactly {depth} levels""",

        DatabaseType.VECTOR: """DEFINITION OF RELATIONSHIP DEPTH:
Relationship depth = the longest chain of references between collections.
References are fields that store IDs pointing to another collection.

Examples:
- depth=1: Collection_A -> Collection_B (1 reference)
- depth=2: Collection_A -> Collection_B -> Collection_C (2 references)

Your task is to:
1. Identify ALL collections and reference fields between them
2. Map the reference graph
3. Find the longest path
4. Check whether the longest path has exactly {depth} levels""",

        DatabaseType.DOCUMENT: """DEFINITION OF RELATIONSHIP DEPTH:
Relationship depth = the longest chain of document references between collections.
References include DBRef, manual references (storing _id of another collection),
or embedded sub-documents that represent separate entities.

Examples:
- depth=1: Collection_A -> Collection_B (1 reference)
- depth=2: Collection_A -> Collection_B -> Collection_C (2 references)
- depth=3: Collection_A -> Collection_B -> Collection_C -> Collection_D (3 references)

Your task is to:
1. Identify ALL collections and their reference/embedding relationships
2. Map the reference graph
3. Find the longest path
4. Check whether the longest path has exactly {depth} levels""",

        DatabaseType.KEY_VALUE: """DEFINITION OF RELATIONSHIP DEPTH:
Relationship depth = the longest chain of key references between data structures.
A reference is when one structure stores a key/ID pointing to another structure.

Examples:
- depth=1: structure_A -> structure_B (1 reference)
- depth=2: structure_A -> structure_B -> structure_C (2 references)

Your task is to:
1. Identify ALL data structures and key references between them
2. Map the reference graph
3. Find the longest path
4. Check whether the longest path has exactly {depth} levels""",

        DatabaseType.TIME_SERIES: """DEFINITION OF RELATIONSHIP DEPTH:
Relationship depth = the longest chain of relationships between measurements/tables.
Relationships include foreign keys (SQL-based), tag references, or lookup references.

Examples:
- depth=1: Measurement_A -> Measurement_B (1 reference)
- depth=2: Measurement_A -> Measurement_B -> Measurement_C (2 references)

Your task is to:
1. Identify ALL measurements/hypertables and their relationships
2. Map the relationship graph
3. Find the longest path
4. Check whether the longest path has exactly {depth} levels""",
    }

    def validate(self, config: DatabaseConfig, script: str) -> ValidationResult:
        depth_definition = self._DEPTH_DEFINITION[config.db_type].format(depth=config.depth)

        system_prompt = f"""You are a data modeling expert. Your task is to analyze
the relationship depth in a {config.db_type.value} database script for {config.db_name}.

{depth_definition}

REQUIRED DEPTH: {config.depth}

Use the validate tool to return your assessment."""

        user_prompt = (
            f"Required relationship depth: {config.depth}\n\n"
            f"Script to analyze:\n\n{script}"
        )

        result = self._validate_with_tool_use(system_prompt, user_prompt)
        logger.info("[%s] Result: %s — %s", self.name, result.status.value, result.feedback)
        return result
