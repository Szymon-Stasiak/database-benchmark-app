from __future__ import annotations

import pytest
from pydantic import ValidationError

from dbagnets.models import (
    AbstractDataType,
    GeneratedSchemaResponse,
    GeneratedScript,
    RelationshipCardinality,
    ValidationStatus,
    ValidatorResponse,
)
from dbagnets.models.llm_schemas import (
    SchemaAttributeResponse,
    SchemaEntityResponse,
    SchemaRelationshipResponse,
)


class TestGeneratedScript:
    def test_stores_script_field(self):
        gs = GeneratedScript(script="CREATE TABLE t;")
        assert gs.script == "CREATE TABLE t;"

    def test_rejects_missing_script(self):
        with pytest.raises(ValidationError):
            GeneratedScript()


class TestValidatorResponse:
    def test_stores_all_fields(self):
        vr = ValidatorResponse(
            status=ValidationStatus.PASS, feedback="OK", details="None"
        )
        assert vr.status == ValidationStatus.PASS
        assert vr.feedback == "OK"
        assert vr.details == "None"

    def test_rejects_missing_fields(self):
        with pytest.raises(ValidationError):
            ValidatorResponse(status=ValidationStatus.PASS)


class TestSchemaAttributeResponse:
    def test_stores_name_and_data_type(self):
        attr = SchemaAttributeResponse(
            name="user_id", data_type=AbstractDataType.INTEGER
        )
        assert attr.name == "user_id"
        assert attr.data_type == AbstractDataType.INTEGER

    def test_has_correct_defaults_for_optional_fields(self):
        attr = SchemaAttributeResponse(
            name="email", data_type=AbstractDataType.STRING
        )
        assert attr.description == ""
        assert attr.vector_dimensions is None
        assert attr.enum_values == []
        assert attr.precision is None
        assert attr.scale is None


class TestSchemaEntityResponse:
    def test_stores_name_and_attributes(self):
        attr = SchemaAttributeResponse(
            name="id", data_type=AbstractDataType.UUID
        )
        entity = SchemaEntityResponse(name="users", attributes=[attr])
        assert entity.name == "users"
        assert len(entity.attributes) == 1
        assert entity.attributes[0].name == "id"


class TestSchemaRelationshipResponse:
    def test_stores_all_required_fields(self):
        rel = SchemaRelationshipResponse(
            name="user_orders",
            source_entity="users",
            target_entity="orders",
            cardinality=RelationshipCardinality.ONE_TO_MANY,
        )
        assert rel.name == "user_orders"
        assert rel.source_entity == "users"
        assert rel.target_entity == "orders"
        assert rel.cardinality == RelationshipCardinality.ONE_TO_MANY
        assert rel.description == ""
        assert rel.attributes == []


class TestGeneratedSchemaResponse:
    def test_stores_entities_and_relationships(self):
        attr = SchemaAttributeResponse(
            name="id", data_type=AbstractDataType.INTEGER
        )
        entity = SchemaEntityResponse(name="users", attributes=[attr])
        rel = SchemaRelationshipResponse(
            name="user_orders",
            source_entity="users",
            target_entity="orders",
            cardinality=RelationshipCardinality.ONE_TO_MANY,
        )
        schema = GeneratedSchemaResponse(
            depth_chain=["users", "orders"],
            entities=[entity], relationships=[rel],
        )
        assert len(schema.entities) == 1
        assert len(schema.relationships) == 1
        assert schema.depth_chain == ["users", "orders"]

    def test_data_size_hints_defaults_to_empty_list(self):
        attr = SchemaAttributeResponse(
            name="id", data_type=AbstractDataType.INTEGER
        )
        entity = SchemaEntityResponse(name="users", attributes=[attr])
        schema = GeneratedSchemaResponse(
            depth_chain=["users"],
            entities=[entity], relationships=[],
        )
        assert schema.data_size_hints == []
