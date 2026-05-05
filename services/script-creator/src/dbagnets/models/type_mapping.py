from __future__ import annotations

from dbagnets.models.enums import AbstractDataType

TYPE_MAPPINGS: dict[AbstractDataType, dict[str, str]] = {
    AbstractDataType.STRING: {
        "postgresql": "VARCHAR(255)",
        "mysql": "VARCHAR(255)",
        "neo4j": "String",
        "mongodb": "string",
        "redis": "string",
        "milvus": "VARCHAR",
        "timescaledb": "VARCHAR(255)",
        "influxdb": "string",
        "cassandra": "TEXT",
    },
    AbstractDataType.TEXT: {
        "postgresql": "TEXT",
        "mysql": "TEXT",
        "neo4j": "String",
        "mongodb": "string",
        "redis": "string",
        "milvus": "VARCHAR",
        "timescaledb": "TEXT",
        "influxdb": "string",
        "cassandra": "TEXT",
    },
    AbstractDataType.INTEGER: {
        "postgresql": "INTEGER",
        "mysql": "INT",
        "neo4j": "Integer",
        "mongodb": "int",
        "redis": "integer (in hash)",
        "milvus": "INT32",
        "timescaledb": "INTEGER",
        "influxdb": "integer",
        "cassandra": "INT",
    },
    AbstractDataType.BIGINT: {
        "postgresql": "BIGINT",
        "mysql": "BIGINT",
        "neo4j": "Integer",
        "mongodb": "long",
        "redis": "integer (in hash)",
        "milvus": "INT64",
        "timescaledb": "BIGINT",
        "influxdb": "integer",
        "cassandra": "BIGINT",
    },
    AbstractDataType.FLOAT: {
        "postgresql": "REAL",
        "mysql": "FLOAT",
        "neo4j": "Float",
        "mongodb": "double",
        "redis": "float (in hash)",
        "milvus": "FLOAT",
        "timescaledb": "REAL",
        "influxdb": "float",
        "cassandra": "FLOAT",
    },
    AbstractDataType.DOUBLE: {
        "postgresql": "DOUBLE PRECISION",
        "mysql": "DOUBLE",
        "neo4j": "Float",
        "mongodb": "double",
        "redis": "float (in hash)",
        "milvus": "DOUBLE",
        "timescaledb": "DOUBLE PRECISION",
        "influxdb": "float",
        "cassandra": "DOUBLE",
    },
    AbstractDataType.DECIMAL: {
        "postgresql": "NUMERIC({precision},{scale})",
        "mysql": "DECIMAL({precision},{scale})",
        "neo4j": "Float",
        "mongodb": "decimal",
        "timescaledb": "NUMERIC({precision},{scale})",
        "cassandra": "DECIMAL",
    },
    AbstractDataType.BOOLEAN: {
        "postgresql": "BOOLEAN",
        "mysql": "BOOLEAN",
        "neo4j": "Boolean",
        "mongodb": "bool",
        "redis": "integer (0/1)",
        "milvus": "BOOL",
        "timescaledb": "BOOLEAN",
        "cassandra": "BOOLEAN",
    },
    AbstractDataType.DATE: {
        "postgresql": "DATE",
        "mysql": "DATE",
        "neo4j": "Date",
        "mongodb": "date",
        "timescaledb": "DATE",
        "influxdb": "timestamp",
        "cassandra": "DATE",
    },
    AbstractDataType.TIMESTAMP: {
        "postgresql": "TIMESTAMP WITH TIME ZONE",
        "mysql": "DATETIME",
        "neo4j": "DateTime",
        "mongodb": "date",
        "timescaledb": "TIMESTAMPTZ",
        "influxdb": "timestamp",
        "cassandra": "TIMESTAMP",
    },
    AbstractDataType.UUID: {
        "postgresql": "UUID",
        "mysql": "CHAR(36)",
        "neo4j": "String",
        "mongodb": "string",
        "timescaledb": "UUID",
        "cassandra": "UUID",
    },
    AbstractDataType.JSON: {
        "postgresql": "JSONB",
        "mysql": "JSON",
        "neo4j": "String",
        "mongodb": "object",
        "redis": "JSON",
        "timescaledb": "JSONB",
    },
    AbstractDataType.VECTOR: {
        "postgresql": "vector({dimensions})",
        "milvus": "FLOAT_VECTOR",
        "neo4j": "LIST<FLOAT>",
        "mongodb": "array (with vector search index)",
        "redis": "VECTOR",
    },
    AbstractDataType.ENUM: {
        "postgresql": "VARCHAR(50)",
        "mysql": "ENUM({values})",
        "neo4j": "String",
        "mongodb": "string",
        "redis": "string",
        "timescaledb": "VARCHAR(50)",
        "cassandra": "TEXT",
    },
}


def get_type_hint(abstract_type: AbstractDataType, db_name: str) -> str | None:
    db_mappings = TYPE_MAPPINGS.get(abstract_type, {})
    return db_mappings.get(db_name.lower())


def get_all_type_hints(db_name: str) -> dict[AbstractDataType, str]:
    result: dict[AbstractDataType, str] = {}
    for abstract_type, db_mappings in TYPE_MAPPINGS.items():
        concrete = db_mappings.get(db_name.lower())
        if concrete:
            result[abstract_type] = concrete
    return result


def format_type_mapping_prompt(db_name: str) -> str:
    hints = get_all_type_hints(db_name)
    if not hints:
        return ""
    lines = ["DATA TYPE MAPPING (abstract type -> concrete type):"]
    for abstract_type, concrete in hints.items():
        lines.append(f"  {abstract_type.value} -> {concrete}")
    return "\n".join(lines)
