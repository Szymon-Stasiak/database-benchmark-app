from __future__ import annotations

from dbagnets.models import (
    AbstractDataType,
    DatabaseType,
    RelationshipCardinality,
    ValidationStatus,
)


class TestDatabaseType:
    def test_contains_all_six_supported_types(self):
        assert DatabaseType.RELATIONAL.value == "relational"
        assert DatabaseType.GRAPH.value == "graph"
        assert DatabaseType.VECTOR.value == "vector"
        assert DatabaseType.DOCUMENT.value == "document"
        assert DatabaseType.KEY_VALUE.value == "key_value"
        assert DatabaseType.TIME_SERIES.value == "time_series"


class TestValidationStatus:
    def test_contains_pass_and_fail(self):
        assert ValidationStatus.PASS.value == "PASS"
        assert ValidationStatus.FAIL.value == "FAIL"


class TestAbstractDataType:
    def test_contains_all_fourteen_values(self):
        assert AbstractDataType.STRING.value == "string"
        assert AbstractDataType.TEXT.value == "text"
        assert AbstractDataType.INTEGER.value == "integer"
        assert AbstractDataType.BIGINT.value == "bigint"
        assert AbstractDataType.FLOAT.value == "float"
        assert AbstractDataType.DOUBLE.value == "double"
        assert AbstractDataType.DECIMAL.value == "decimal"
        assert AbstractDataType.BOOLEAN.value == "boolean"
        assert AbstractDataType.DATE.value == "date"
        assert AbstractDataType.TIMESTAMP.value == "timestamp"
        assert AbstractDataType.UUID.value == "uuid"
        assert AbstractDataType.JSON.value == "json"
        assert AbstractDataType.VECTOR.value == "vector"
        assert AbstractDataType.ENUM.value == "enum"


class TestRelationshipCardinality:
    def test_contains_all_three_values(self):
        assert RelationshipCardinality.ONE_TO_ONE.value == "1:1"
        assert RelationshipCardinality.ONE_TO_MANY.value == "1:N"
        assert RelationshipCardinality.MANY_TO_MANY.value == "M:N"
