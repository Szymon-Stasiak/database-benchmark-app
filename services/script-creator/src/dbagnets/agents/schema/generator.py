from __future__ import annotations

import logging

from dbagnets.agents.base import BaseAgent
from dbagnets.models.llm_schemas import GeneratedSchemaResponse
from dbagnets.models.schema import (
    Attribute,
    AttributeConstraint,
    DataSizeHint,
    Entity,
    LogicalSchema,
    Relationship,
)
from dbagnets.models.validation import ValidationResult

logger = logging.getLogger("dbagnets")


class SchemaGeneratorAgent(BaseAgent):

    @property
    def name(self) -> str:
        return "SchemaGenerator"

    @property
    def role_description(self) -> str:
        return "Generates technology-independent logical schemas."

    def generate(
        self,
        idea: str,
        depth: int,
        feedback: list[ValidationResult] | None = None,
        previous_schema_json: str | None = None,
    ) -> LogicalSchema:
        system_prompt = self._build_system_prompt(idea, depth)
        user_prompt = self._build_user_prompt(idea, depth, feedback, previous_schema_json)

        if feedback:
            failed_names = [v.agent_name for v in feedback if not v.passed]
            logger.info("[SchemaGenerator] Regenerating schema with feedback from: %s", ", ".join(failed_names))
        else:
            logger.info("[SchemaGenerator] Generating initial schema")

        result = self._call_llm_structured(
            system_prompt, user_prompt, GeneratedSchemaResponse, "generate_schema",
            max_tokens=16384,
        )

        schema = self._to_logical_schema(result, idea, depth)
        logger.info(
            "[SchemaGenerator] Generated schema: %d entities, %d relationships",
            len(schema.entities), len(schema.relationships),
        )
        return schema

    def _to_logical_schema(
        self, response: GeneratedSchemaResponse, idea: str, depth: int
    ) -> LogicalSchema:
        entities = [
            Entity(
                name=e.name,
                description=e.description,
                attributes=[
                    Attribute(
                        name=a.name,
                        data_type=a.data_type,
                        constraints=AttributeConstraint(
                            is_primary_key=a.constraints.is_primary_key,
                            is_unique=a.constraints.is_unique,
                            is_nullable=a.constraints.is_nullable,
                            is_indexed=a.constraints.is_indexed,
                            default_value=a.constraints.default_value,
                        ),
                        description=a.description,
                        vector_dimensions=a.vector_dimensions,
                        enum_values=a.enum_values,
                        precision=a.precision,
                        scale=a.scale,
                    )
                    for a in e.attributes
                ],
            )
            for e in response.entities
        ]

        relationships = [
            Relationship(
                name=r.name,
                source_entity=r.source_entity,
                target_entity=r.target_entity,
                cardinality=r.cardinality,
                description=r.description,
                attributes=[
                    Attribute(
                        name=a.name,
                        data_type=a.data_type,
                        constraints=AttributeConstraint(
                            is_primary_key=a.constraints.is_primary_key,
                            is_unique=a.constraints.is_unique,
                            is_nullable=a.constraints.is_nullable,
                            is_indexed=a.constraints.is_indexed,
                            default_value=a.constraints.default_value,
                        ),
                        description=a.description,
                        vector_dimensions=a.vector_dimensions,
                        enum_values=a.enum_values,
                        precision=a.precision,
                        scale=a.scale,
                    )
                    for a in r.attributes
                ],
            )
            for r in response.relationships
        ]

        data_size_hints = [
            DataSizeHint(
                entity_name=h.entity_name,
                expected_row_count=h.expected_row_count,
            )
            for h in response.data_size_hints
        ]

        return LogicalSchema(
            idea=idea,
            depth=depth,
            depth_chain=response.depth_chain,
            entities=entities,
            relationships=relationships,
            data_size_hints=data_size_hints,
        )

    def _build_system_prompt(self, idea: str, depth: int) -> str:
        depth_example_chain = " -> ".join(
            f"Entity_{chr(65 + i)}" for i in range(depth + 1)
        )

        return f"""You are a database architecture expert designing schemas for PRODUCTION systems.
Your task is to design a technology-independent logical schema for the given idea.

STEP-BY-STEP APPROACH:
1. FIRST, plan the depth_chain — a list of {depth + 1} entity names that form a
   natural, semantically meaningful chain of {depth} directed 1:N relationships.
   Example for depth={depth}: [{depth_example_chain}]
   Each consecutive pair MUST have a 1:N relationship from left to right.
   ALL entities in the chain MUST be semantically relevant to: "{idea}".
   Do NOT create artificial relationships just to reach the depth — every link must
   make domain sense.

2. THEN, design the full schema around this chain:
   - Add attributes to each entity (with abstract types).
   - Add any additional entities and M:N or other relationships to enrich the schema.
   - The depth_chain entities are the backbone — do NOT remove them.
   - CRITICAL: The longest directed path in the ENTIRE graph must be exactly {depth} hops.
     Do NOT add 1:N relationships that extend beyond the chain's start or end entities.
     Additional relationships (M:N, reverse 1:N, or between non-adjacent chain entities)
     are fine as long as they don't create a directed path longer than {depth}.

OUTPUT FORMAT:
- depth_chain: list of {depth + 1} entity names forming the main chain (left to right).
- entities: all entities with their attributes.
- relationships: all relationships. The chain relationships MUST appear here as
  1:N relationships matching the depth_chain order.
- data_size_hints: expected row counts per entity.

RULES:
1. Abstract data types: string, text, integer, bigint, float, double, decimal, boolean, date, timestamp, uuid, json, vector, enum.
2. Cardinalities: 1:1, 1:N, M:N.
3. Every entity must have a primary key attribute.
4. Mark indexed attributes (frequently queried fields).
5. Use snake_case naming in English.
6. For M:N relationships, do NOT create junction entities.
7. The schema must be rich but not overly complex.

PRODUCTION-SCALE DESIGN:
- data_size_hints MUST reflect realistic production volumes (thousands to millions).
  These drive partitioning, indexing, and storage decisions in script generation.
- Each entity should have 5-15 attributes to make benchmarks meaningful.
- Include a mix of data types per entity: strings, numerics, timestamps, booleans, enums.
- Include at least one entity with an array/list-like attribute (tags, categories).
- Include at least one text/description field suitable for full-text search.
- Ensure at least one timestamp attribute exists for time-based query benchmarks.
- Avoid extreme attribute counts (>30 per entity) — some databases have field limits.
- Avoid designs that would create supernodes (single nodes with millions of edges)
  in graph databases — spread connectivity across entities.

Use the generate_schema tool to return the schema."""

    def _build_user_prompt(
        self,
        idea: str,
        depth: int,
        feedback: list[ValidationResult] | None,
        previous_schema_json: str | None,
    ) -> str:
        if feedback and previous_schema_json:
            feedback_parts = []
            for v in feedback:
                if v.passed:
                    continue
                part = f"- [{v.agent_name}] {v.feedback}"
                if v.todos:
                    part += "\n  TODO:\n" + "\n".join(f"    - {t}" for t in v.todos)
                elif v.details:
                    part += f"\n  Details: {v.details}"
                feedback_parts.append(part)
            feedback_text = "\n".join(feedback_parts)
            return (
                f"Idea: {idea}\n"
                f"Required relationship depth: {depth}\n\n"
                f"Previous schema (needs fixing):\n{previous_schema_json}\n\n"
                f"Validator feedback (fix these issues):\n{feedback_text}\n\n"
                "Generate a corrected schema addressing all feedback."
            )

        return (
            f"Idea: {idea}\n"
            f"Required relationship depth: {depth}\n\n"
            "Generate a complete logical schema."
        )
