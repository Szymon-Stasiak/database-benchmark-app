from __future__ import annotations

import operator
from typing import Annotated, TypedDict

from pydantic import BaseModel

from models.config import DatabaseConfig
from models.validation import IterationResult, ValidationResult


class LoopState(BaseModel):
    config: DatabaseConfig
    max_iterations: int = 10
    current_iteration: int = 0
    history: list[IterationResult] = []
    final_script: str | None = None
    success: bool = False


class GraphState(TypedDict):
    config: DatabaseConfig
    max_iterations: int
    current_iteration: int
    script: str | None
    feedback: list[ValidationResult]
    history: Annotated[list[IterationResult], operator.add]
    final_script: str | None
    success: bool
