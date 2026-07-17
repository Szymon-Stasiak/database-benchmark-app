from __future__ import annotations

import logging

from dbagnets.models.enums import AbstractDataType, RelationshipCardinality
from dbagnets.models.schema import (
    Attribute,
    AttributeConstraint,
    Entity,
    LogicalSchema,
    Relationship,
)

logger = logging.getLogger("dbagnets")


def reconcile_fk_columns(schema: LogicalSchema) -> LogicalSchema:
    fk_columns_by_child = _derive_fk_columns(schema.relationships)
    new_entities = [_augment_entity(e, fk_columns_by_child.get(e.name, [])) for e in schema.entities]
    new_relationships = [_assign_fk_column(r) for r in schema.relationships]

    if _is_unchanged(schema, new_entities, new_relationships):
        return schema

    return LogicalSchema(
        idea=schema.idea,
        depth=schema.depth,
        depth_chain=list(schema.depth_chain),
        entities=new_entities,
        relationships=new_relationships,
        data_size_hints=list(schema.data_size_hints),
    )


def _derive_fk_columns(relationships: list[Relationship]) -> dict[str, list[tuple[str, str]]]:
    result: dict[str, list[tuple[str, str]]] = {}
    for rel in relationships:
        if rel.cardinality == RelationshipCardinality.MANY_TO_MANY:
            continue
        fk_name = _canonical_fk_name(rel.source_entity)
        result.setdefault(rel.target_entity, []).append((fk_name, rel.source_entity))
    return result


def _augment_entity(entity: Entity, fks: list[tuple[str, str]]) -> Entity:
    existing_names = {a.name for a in entity.attributes}
    additions: list[Attribute] = []
    seen_in_additions: set[str] = set()
    for fk_name, parent in fks:
        if fk_name in existing_names or fk_name in seen_in_additions:
            continue
        additions.append(_fk_attribute(fk_name, parent))
        seen_in_additions.add(fk_name)

    if not additions:
        return entity

    logger.info(
        "[FKReconciler] injecting FK columns into '%s': %s",
        entity.name,
        ", ".join(a.name for a in additions),
    )
    return Entity(
        name=entity.name,
        description=entity.description,
        attributes=list(entity.attributes) + additions,
    )


def _assign_fk_column(rel: Relationship) -> Relationship:
    if rel.cardinality == RelationshipCardinality.MANY_TO_MANY:
        return rel
    canonical = _canonical_fk_name(rel.source_entity)
    if rel.fk_column_in_child == canonical:
        return rel
    return Relationship(
        name=rel.name,
        source_entity=rel.source_entity,
        target_entity=rel.target_entity,
        cardinality=rel.cardinality,
        description=rel.description,
        attributes=list(rel.attributes),
        fk_column_in_child=canonical,
    )


def _fk_attribute(name: str, parent_entity: str) -> Attribute:
    return Attribute(
        name=name,
        data_type=AbstractDataType.UUID,
        constraints=AttributeConstraint(is_nullable=True, is_indexed=True),
        description=f"Foreign key referencing {parent_entity}.",
    )


def _canonical_fk_name(parent_entity: str) -> str:
    return _to_snake_case(parent_entity) + "_id"


def _to_snake_case(name: str) -> str:
    out: list[str] = []
    for i, ch in enumerate(name):
        if i > 0 and ch.isupper() and not name[i - 1].isupper():
            out.append("_")
        out.append(ch.lower())
    return "".join(out)


def _is_unchanged(
    schema: LogicalSchema,
    new_entities: list[Entity],
    new_relationships: list[Relationship],
) -> bool:
    return schema.entities == new_entities and schema.relationships == new_relationships
