from __future__ import annotations

from dbagnets.models.enums import AbstractDataType, RelationshipCardinality
from dbagnets.models.schema import (
    Attribute,
    AttributeConstraint,
    Entity,
    LogicalSchema,
    Relationship,
)
from dbagnets.orchestrators.fk_reconciler import reconcile_fk_columns


def _pk(name: str) -> Attribute:
    return Attribute(
        name=name,
        data_type=AbstractDataType.UUID,
        constraints=AttributeConstraint(is_primary_key=True, is_nullable=False),
    )


def _string(name: str) -> Attribute:
    return Attribute(name=name, data_type=AbstractDataType.STRING)


def _entity(name: str, attrs: list[Attribute]) -> Entity:
    return Entity(name=name, attributes=attrs)


def _rel(name: str, parent: str, child: str, cardinality: RelationshipCardinality, fk: str | None = None) -> Relationship:
    return Relationship(
        name=name,
        source_entity=parent,
        target_entity=child,
        cardinality=cardinality,
        fk_column_in_child=fk,
    )


def _schema(entities: list[Entity], rels: list[Relationship]) -> LogicalSchema:
    return LogicalSchema(idea="x", depth=1, entities=entities, relationships=rels)


class TestReconcileFkColumns:
    def test_injects_missing_fk_attribute_and_sets_relationship(self):
        owner = _entity("Owner", [_pk("owner_id"), _string("full_name")])
        car = _entity("ClassicCar", [_pk("car_id"), _string("model_name")])
        rel = _rel("owner_owns_cars", "Owner", "ClassicCar", RelationshipCardinality.ONE_TO_MANY)
        schema = _schema([owner, car], [rel])

        result = reconcile_fk_columns(schema)

        new_car = result.get_entity("ClassicCar")
        assert any(a.name == "owner_id" and a.data_type == AbstractDataType.UUID for a in new_car.attributes)
        assert result.relationships[0].fk_column_in_child == "owner_id"

    def test_preserves_existing_fk_attribute_when_canonical_already_present(self):
        manufacturer = _entity("Manufacturer", [_pk("manufacturer_id")])
        car = _entity(
            "ClassicCar",
            [_pk("car_id"), Attribute(name="manufacturer_id", data_type=AbstractDataType.UUID)],
        )
        rel = _rel("manufacturer_produces_cars", "Manufacturer", "ClassicCar", RelationshipCardinality.ONE_TO_MANY)
        schema = _schema([manufacturer, car], [rel])

        result = reconcile_fk_columns(schema)

        new_car = result.get_entity("ClassicCar")
        manufacturer_attrs = [a for a in new_car.attributes if a.name == "manufacturer_id"]
        assert len(manufacturer_attrs) == 1
        assert result.relationships[0].fk_column_in_child == "manufacturer_id"

    def test_skips_many_to_many_relationships(self):
        task = _entity("Task", [_pk("task_id")])
        part = _entity("Part", [_pk("part_id")])
        rel = _rel("tasks_use_parts", "Task", "Part", RelationshipCardinality.MANY_TO_MANY)
        schema = _schema([task, part], [rel])

        result = reconcile_fk_columns(schema)

        new_part = result.get_entity("Part")
        assert all(a.name != "task_id" for a in new_part.attributes)
        assert result.relationships[0].fk_column_in_child is None

    def test_multiple_parents_each_get_own_fk_column(self):
        owner = _entity("Owner", [_pk("owner_id")])
        manufacturer = _entity("Manufacturer", [_pk("manufacturer_id")])
        car = _entity("ClassicCar", [_pk("car_id")])
        rels = [
            _rel("owner_owns_cars", "Owner", "ClassicCar", RelationshipCardinality.ONE_TO_MANY),
            _rel("mfr_makes_cars", "Manufacturer", "ClassicCar", RelationshipCardinality.ONE_TO_MANY),
        ]
        schema = _schema([owner, manufacturer, car], rels)

        result = reconcile_fk_columns(schema)

        new_car = result.get_entity("ClassicCar")
        column_names = {a.name for a in new_car.attributes}
        assert {"owner_id", "manufacturer_id"}.issubset(column_names)
        fk_set = {r.fk_column_in_child for r in result.relationships}
        assert fk_set == {"owner_id", "manufacturer_id"}

    def test_is_idempotent(self):
        owner = _entity("Owner", [_pk("owner_id")])
        car = _entity("ClassicCar", [_pk("car_id")])
        rel = _rel("owner_owns_cars", "Owner", "ClassicCar", RelationshipCardinality.ONE_TO_MANY)
        schema = _schema([owner, car], [rel])

        once = reconcile_fk_columns(schema)
        twice = reconcile_fk_columns(once)

        assert once.model_dump_json() == twice.model_dump_json()

    def test_canonical_name_uses_snake_case_for_camel_case_parents(self):
        project = _entity("RestorationProject", [_pk("project_id")])
        task = _entity("RestorationTask", [_pk("task_id")])
        rel = _rel("project_has_tasks", "RestorationProject", "RestorationTask", RelationshipCardinality.ONE_TO_MANY)
        schema = _schema([project, task], [rel])

        result = reconcile_fk_columns(schema)

        new_task = result.get_entity("RestorationTask")
        assert any(a.name == "restoration_project_id" for a in new_task.attributes)
        assert result.relationships[0].fk_column_in_child == "restoration_project_id"
