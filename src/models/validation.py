from __future__ import annotations

from pydantic import BaseModel

from models.enums import ValidationStatus


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
