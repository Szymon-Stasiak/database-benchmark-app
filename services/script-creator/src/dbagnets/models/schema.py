from __future__ import annotations

from pydantic import BaseModel, ConfigDict

from dbagnets.models.enums import AbstractDataType, RelationshipCardinality


class AttributeConstraint(BaseModel):
    model_config = ConfigDict(frozen=True)

    is_primary_key: bool = False
    is_unique: bool = False
    is_nullable: bool = True
    is_indexed: bool = False
    default_value: str | None = None


class Attribute(BaseModel):
    model_config = ConfigDict(frozen=True)

    name: str
    data_type: AbstractDataType
    constraints: AttributeConstraint = AttributeConstraint()
    description: str = ""
    vector_dimensions: int | None = None
    enum_values: list[str] = []
    precision: int | None = None
    scale: int | None = None


class Entity(BaseModel):
    model_config = ConfigDict(frozen=True)

    name: str
    description: str = ""
    attributes: list[Attribute]


class Relationship(BaseModel):
    model_config = ConfigDict(frozen=True)

    name: str
    source_entity: str
    target_entity: str
    cardinality: RelationshipCardinality
    description: str = ""
    attributes: list[Attribute] = []
    fk_column_in_child: str | None = None


class DocumentEmbeddingMapping(BaseModel):
    model_config = ConfigDict(frozen=True)

    entity_name: str
    is_embedded: bool
    parent_entity: str | None = None
    field_name: str | None = None


class DataSizeHint(BaseModel):
    model_config = ConfigDict(frozen=True)

    entity_name: str
    expected_row_count: int


class LogicalSchema(BaseModel):
    model_config = ConfigDict(frozen=True)

    idea: str
    depth: int
    depth_chain: list[str] = []
    entities: list[Entity]
    relationships: list[Relationship]
    data_size_hints: list[DataSizeHint] = []

    @property
    def entity_names(self) -> list[str]:
        return [e.name for e in self.entities]

    def get_entity(self, name: str) -> Entity | None:
        return next((e for e in self.entities if e.name == name), None)
