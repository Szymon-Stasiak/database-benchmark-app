from __future__ import annotations

from enum import Enum


class DatabaseType(Enum):
    RELATIONAL = "relational"
    GRAPH = "graph"
    VECTOR = "vector"
    DOCUMENT = "document"
    KEY_VALUE = "key_value"
    TIME_SERIES = "time_series"


class ValidationStatus(Enum):
    PASS = "PASS"
    FAIL = "FAIL"


class AbstractDataType(Enum):
    STRING = "string"
    TEXT = "text"
    INTEGER = "integer"
    BIGINT = "bigint"
    FLOAT = "float"
    DOUBLE = "double"
    DECIMAL = "decimal"
    BOOLEAN = "boolean"
    DATE = "date"
    TIMESTAMP = "timestamp"
    UUID = "uuid"
    JSON = "json"
    VECTOR = "vector"
    ENUM = "enum"


class RelationshipCardinality(Enum):
    ONE_TO_ONE = "1:1"
    ONE_TO_MANY = "1:N"
    MANY_TO_MANY = "M:N"
