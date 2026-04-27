from __future__ import annotations

import pytest
from pydantic import ValidationError

from models import GeneratedScript, ValidationStatus, ValidatorResponse


class TestGeneratedScript:
    def test_stores_script_field(self):
        gs = GeneratedScript(script="CREATE TABLE t;")
        assert gs.script == "CREATE TABLE t;"

    def test_rejects_missing_script(self):
        with pytest.raises(ValidationError):
            GeneratedScript()


class TestValidatorResponse:
    def test_stores_all_fields(self):
        vr = ValidatorResponse(
            status=ValidationStatus.PASS, feedback="OK", details="None"
        )
        assert vr.status == ValidationStatus.PASS
        assert vr.feedback == "OK"
        assert vr.details == "None"

    def test_rejects_missing_fields(self):
        with pytest.raises(ValidationError):
            ValidatorResponse(status=ValidationStatus.PASS)
