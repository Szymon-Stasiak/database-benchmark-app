from __future__ import annotations

import operator
from typing import Annotated, TypedDict

from pydantic import BaseModel

from dbagnets.models.config import TargetConfig
from dbagnets.models.schema import DocumentEmbeddingMapping
from dbagnets.models.validation import IterationResult, ValidationResult


class SchemaGraphState(TypedDict):
    idea: str
    depth: int
    max_iterations: int
    current_iteration: int
    schema_json: str | None
    feedback: list[ValidationResult]
    history: Annotated[list[IterationResult], operator.add]
    final_schema_json: str | None
    success: bool


class ScriptGraphState(TypedDict):
    target: TargetConfig
    schema_json: str
    idea: str
    depth: int
    max_iterations: int
    current_iteration: int
    script: str | None
    embedding_mappings: list[DocumentEmbeddingMapping]
    feedback: list[ValidationResult]
    history: Annotated[list[IterationResult], operator.add]
    final_script: str | None
    success: bool


class SchemaLoopState(BaseModel):
    idea: str
    depth: int
    max_iterations: int = 10
    current_iteration: int = 0
    history: list[IterationResult] = []
    final_schema_json: str | None = None
    success: bool = False


class ScriptLoopState(BaseModel):
    target: TargetConfig
    max_iterations: int = 10
    current_iteration: int = 0
    history: list[IterationResult] = []
    final_script: str | None = None
    embedding_mappings: list[DocumentEmbeddingMapping] = []
    success: bool = False


class PipelineResult(BaseModel):
    schema_result: SchemaLoopState
    script_results: list[ScriptLoopState]

    @property
    def all_succeeded(self) -> bool:
        return self.schema_result.success and all(
            r.success for r in self.script_results
        )
