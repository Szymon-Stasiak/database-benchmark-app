from __future__ import annotations

from pydantic import BaseModel

from dbagnets.models.enums import (
    AbstractDataType,
    RelationshipCardinality,
    ValidationStatus,
)


class GeneratedScript(BaseModel):
    script: str


class ValidatorResponse(BaseModel):
    status: ValidationStatus
    feedback: str
    details: str
    todos: list[str] = []


class SchemaAttributeConstraintResponse(BaseModel):
    is_primary_key: bool = False
    is_unique: bool = False
    is_nullable: bool = True
    is_indexed: bool = False
    default_value: str | None = None


class SchemaAttributeResponse(BaseModel):
    name: str
    data_type: AbstractDataType
    constraints: SchemaAttributeConstraintResponse = SchemaAttributeConstraintResponse()
    description: str = ""
    vector_dimensions: int | None = None
    enum_values: list[str] = []
    precision: int | None = None
    scale: int | None = None


class SchemaEntityResponse(BaseModel):
    name: str
    description: str = ""
    attributes: list[SchemaAttributeResponse]


class SchemaRelationshipResponse(BaseModel):
    name: str
    source_entity: str
    target_entity: str
    cardinality: RelationshipCardinality
    description: str = ""
    attributes: list[SchemaAttributeResponse] = []


class SchemaDataSizeHintResponse(BaseModel):
    entity_name: str
    expected_row_count: int


class GeneratedSchemaResponse(BaseModel):
    depth_chain: list[str]
    entities: list[SchemaEntityResponse]
    relationships: list[SchemaRelationshipResponse]
    data_size_hints: list[SchemaDataSizeHintResponse] = []
