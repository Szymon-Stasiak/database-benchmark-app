from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol, runtime_checkable

from dbagnets.models.config import TargetConfig
from dbagnets.models.database_profile import DatabaseProfile, get_profile
from dbagnets.models.schema import DocumentEmbeddingMapping, LogicalSchema
from dbagnets.models.validation import ValidationResult


@dataclass(frozen=True)
class ValidationContext:
    schema: LogicalSchema
    target: TargetConfig | None = None
    script: str | None = None
    embedding_mappings: tuple[DocumentEmbeddingMapping, ...] = ()
    idea: str = ""
    depth: int = 0

    @property
    def profile(self) -> DatabaseProfile:
        if self.target is None:
            raise ValueError("ValidationContext.profile requires a target")
        return get_profile(self.target.db_type)


@runtime_checkable
class Validator(Protocol):
    @property
    def name(self) -> str: ...
    def validate(self, ctx: ValidationContext) -> ValidationResult: ...