from __future__ import annotations

from dataclasses import dataclass, field
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


@dataclass
class DatabaseConfig:
    db_type: DatabaseType
    db_name: str          # e.g. "postgresql", "neo4j", "milvus"
    db_version: str       # e.g. "13", "5.0", "2.3"
    idea: str             # e.g. "movie management database"
    depth: int            # relationship depth, e.g. 4


@dataclass
class ValidationResult:
    agent_name: str
    status: ValidationStatus
    feedback: str
    details: str = ""

    @property
    def passed(self) -> bool:
        return self.status == ValidationStatus.PASS


@dataclass
class IterationResult:
    iteration: int
    script: str
    validations: list[ValidationResult] = field(default_factory=list)

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


@dataclass
class LoopState:
    config: DatabaseConfig
    max_iterations: int = 10
    current_iteration: int = 0
    history: list[IterationResult] = field(default_factory=list)
    final_script: str | None = None
    success: bool = False