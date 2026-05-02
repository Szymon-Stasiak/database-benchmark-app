from __future__ import annotations

import pytest
from pydantic import ValidationError

from dbagnets.models import (
    Attribute,
    AttributeConstraint,
    DataSizeHint,
    Entity,
    LogicalSchema,
    Relationship,
    RelationshipCardinality,
)
from dbagnets.models.enums import AbstractDataType


class TestAttributeConstraint:
    def test_has_correct_defaults(self):
        ac = AttributeConstraint()
        assert ac.is_primary_key is False
        assert ac.is_unique is False
        assert ac.is_nullable is True
        assert ac.is_indexed is False
        assert ac.default_value is None

    def test_is_frozen(self):
        ac = AttributeConstraint()
        with pytest.raises(ValidationError):
            ac.is_primary_key = True


class TestAttribute:
    def test_stores_all_fields(self):
        constraint = AttributeConstraint(is_primary_key=True, is_nullable=False)
        attr = Attribute(
            name="user_id",
            data_type=AbstractDataType.UUID,
            constraints=constraint,
            description="Primary key",
            vector_dimensions=None,
            enum_values=[],
            precision=None,
            scale=None,
        )
        assert attr.name == "user_id"
        assert attr.data_type == AbstractDataType.UUID
        assert attr.constraints.is_primary_key is True
        assert attr.description == "Primary key"

    def test_has_correct_defaults_for_optional_fields(self):
        attr = Attribute(name="email", data_type=AbstractDataType.STRING)
        assert attr.constraints == AttributeConstraint()
        assert attr.description == ""
        assert attr.vector_dimensions is None
        assert attr.enum_values == []
        assert attr.precision is None
        assert attr.scale is None

    def test_is_frozen(self):
        attr = Attribute(name="email", data_type=AbstractDataType.STRING)
        with pytest.raises(ValidationError):
            attr.name = "changed"


class TestEntity:
    def test_stores_name_and_attributes(self):
        attr = Attribute(name="id", data_type=AbstractDataType.INTEGER)
        entity = Entity(name="users", attributes=[attr])
        assert entity.name == "users"
        assert len(entity.attributes) == 1
        assert entity.attributes[0].name == "id"

    def test_is_frozen(self):
        attr = Attribute(name="id", data_type=AbstractDataType.INTEGER)
        entity = Entity(name="users", attributes=[attr])
        with pytest.raises(ValidationError):
            entity.name = "changed"


class TestRelationship:
    def test_stores_all_fields(self):
        rel = Relationship(
            name="user_orders",
            source_entity="users",
            target_entity="orders",
            cardinality=RelationshipCardinality.ONE_TO_MANY,
            description="A user has many orders",
        )
        assert rel.name == "user_orders"
        assert rel.source_entity == "users"
        assert rel.target_entity == "orders"
        assert rel.cardinality == RelationshipCardinality.ONE_TO_MANY
        assert rel.description == "A user has many orders"

    def test_attributes_defaults_to_empty_list(self):
        rel = Relationship(
            name="user_orders",
            source_entity="users",
            target_entity="orders",
            cardinality=RelationshipCardinality.ONE_TO_MANY,
        )
        assert rel.attributes == []

    def test_is_frozen(self):
        rel = Relationship(
            name="user_orders",
            source_entity="users",
            target_entity="orders",
            cardinality=RelationshipCardinality.ONE_TO_MANY,
        )
        with pytest.raises(ValidationError):
            rel.name = "changed"


class TestDataSizeHint:
    def test_stores_entity_name_and_expected_row_count(self):
        hint = DataSizeHint(entity_name="users", expected_row_count=10000)
        assert hint.entity_name == "users"
        assert hint.expected_row_count == 10000

    def test_is_frozen(self):
        hint = DataSizeHint(entity_name="users", expected_row_count=10000)
        with pytest.raises(ValidationError):
            hint.entity_name = "changed"


class TestLogicalSchema:
    def _make_schema(self):
        attr_id = Attribute(name="id", data_type=AbstractDataType.INTEGER)
        attr_name = Attribute(name="name", data_type=AbstractDataType.STRING)
        users = Entity(name="users", attributes=[attr_id, attr_name])
        orders = Entity(name="orders", attributes=[attr_id])
        rel = Relationship(
            name="user_orders",
            source_entity="users",
            target_entity="orders",
            cardinality=RelationshipCardinality.ONE_TO_MANY,
        )
        return LogicalSchema(
            idea="movie database",
            depth=4,
            entities=[users, orders],
            relationships=[rel],
        )

    def test_stores_all_fields(self):
        schema = self._make_schema()
        assert schema.idea == "movie database"
        assert schema.depth == 4
        assert len(schema.entities) == 2
        assert len(schema.relationships) == 1

    def test_is_frozen(self):
        schema = self._make_schema()
        with pytest.raises(ValidationError):
            schema.idea = "changed"

    def test_entity_names_returns_list_of_names(self):
        schema = self._make_schema()
        assert schema.entity_names == ["users", "orders"]

    def test_get_entity_returns_entity_by_name(self):
        schema = self._make_schema()
        entity = schema.get_entity("users")
        assert entity is not None
        assert entity.name == "users"

    def test_get_entity_returns_none_for_missing(self):
        schema = self._make_schema()
        assert schema.get_entity("nonexistent") is None

    def test_data_size_hints_defaults_to_empty(self):
        schema = self._make_schema()
        assert schema.data_size_hints == []
