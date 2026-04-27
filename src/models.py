from __future__ import annotations

import operator
from enum import Enum
from typing import Annotated, TypedDict

from pydantic import BaseModel, ConfigDict


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


class DatabaseConfig(BaseModel):
    model_config = ConfigDict(frozen=True)

    db_type: DatabaseType
    db_name: str          # e.g. "postgresql", "neo4j", "milvus"
    db_version: str       # e.g. "13", "5.0", "2.3"
    idea: str             # e.g. "movie management database"
    depth: int            # relationship depth, e.g. 4


class ValidationResult(BaseModel):
    agent_name: str
    status: ValidationStatus
    feedback: str
    details: str = ""

    @property
    def passed(self) -> bool:
        return self.status == ValidationStatus.PASS


class IterationResult(BaseModel):
    iteration: int
    script: str
    validations: list[ValidationResult] = []

    @property
    def all_passed(self) -> bool:
        return all(v.passed for v in self.validations)

    @property
    def failed_validations(self) -> list[ValidationResult]:
        return [v for v in self.validations if not v.passed]

    def summary(self) -> str:
        lines = [f"=== Iteration {self.iteration} ==="]
        for v in self.validations:
            icon = "PASS" if v.passed else "FAIL"
            lines.append(f"  [{icon}] {v.agent_name}: {v.feedback}")
        return "\n".join(lines)


class LoopState(BaseModel):
    config: DatabaseConfig
    max_iterations: int = 10
    current_iteration: int = 0
    history: list[IterationResult] = []
    final_script: str | None = None
    success: bool = False


class GeneratedScript(BaseModel):
    script: str


class ValidatorResponse(BaseModel):
    status: ValidationStatus
    feedback: str
    details: str


class GraphState(TypedDict):
    config: DatabaseConfig
    max_iterations: int
    current_iteration: int
    script: str | None
    feedback: list[ValidationResult]
    history: Annotated[list[IterationResult], operator.add]
    final_script: str | None
    success: bool
