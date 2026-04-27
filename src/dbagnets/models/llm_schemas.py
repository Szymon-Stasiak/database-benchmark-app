from __future__ import annotations

from pydantic import BaseModel

from dbagnets.models.enums import ValidationStatus


class GeneratedScript(BaseModel):
    script: str


class ValidatorResponse(BaseModel):
    status: ValidationStatus
    feedback: str
    details: str
