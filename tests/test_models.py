from __future__ import annotations

import pytest
from pydantic import ValidationError

from models import (
    DatabaseConfig,
    DatabaseType,
    GeneratedScript,
    IterationResult,
    LoopState,
    ValidationResult,
    ValidationStatus,
    ValidatorResponse,
)


class TestDatabaseType:
    def test_contains_all_six_supported_types(self):
        assert DatabaseType.RELATIONAL.value == "relational"
        assert DatabaseType.GRAPH.value == "graph"
        assert DatabaseType.VECTOR.value == "vector"
        assert DatabaseType.DOCUMENT.value == "document"
        assert DatabaseType.KEY_VALUE.value == "key_value"
        assert DatabaseType.TIME_SERIES.value == "time_series"


class TestValidationStatus:
    def test_contains_pass_and_fail(self):
        assert ValidationStatus.PASS.value == "PASS"
        assert ValidationStatus.FAIL.value == "FAIL"


class TestDatabaseConfig:
    def test_stores_all_fields_from_constructor(self, sample_config):
        assert sample_config.db_type == DatabaseType.RELATIONAL
        assert sample_config.db_name == "postgresql"
        assert sample_config.db_version == "16"
        assert sample_config.idea == "movie management database"
        assert sample_config.depth == 4

    def test_is_frozen(self, sample_config):
        with pytest.raises(ValidationError):
            sample_config.db_name = "mysql"

    def test_rejects_invalid_db_type(self):
        with pytest.raises(ValidationError):
            DatabaseConfig(
                db_type="invalid", db_name="pg", db_version="16", idea="x", depth=4
            )


class TestValidationResult:
    def test_passed_returns_true_when_status_is_pass(self):
        r = ValidationResult(agent_name="Test", status=ValidationStatus.PASS, feedback="OK")
        assert r.passed is True

    def test_passed_returns_false_when_status_is_fail(self):
        r = ValidationResult(agent_name="Test", status=ValidationStatus.FAIL, feedback="Bad")
        assert r.passed is False

    def test_details_defaults_to_empty_string(self):
        r = ValidationResult(agent_name="Test", status=ValidationStatus.PASS, feedback="OK")
        assert r.details == ""


class TestIterationResult:
    def test_all_passed_returns_true_when_every_validation_passes(self):
        result = IterationResult(
            iteration=1,
            script="SELECT 1;",
            validations=[
                ValidationResult(agent_name="A", status=ValidationStatus.PASS, feedback="OK"),
                ValidationResult(agent_name="B", status=ValidationStatus.PASS, feedback="OK"),
            ],
        )
        assert result.all_passed is True

    def test_all_passed_returns_false_when_any_validation_fails(self):
        result = IterationResult(
            iteration=1,
            script="SELECT 1;",
            validations=[
                ValidationResult(agent_name="A", status=ValidationStatus.PASS, feedback="OK"),
                ValidationResult(agent_name="B", status=ValidationStatus.FAIL, feedback="Bad"),
            ],
        )
        assert result.all_passed is False

    def test_failed_validations_returns_only_failed_results(self):
        fail = ValidationResult(agent_name="B", status=ValidationStatus.FAIL, feedback="Bad")
        result = IterationResult(
            iteration=1,
            script="SELECT 1;",
            validations=[
                ValidationResult(agent_name="A", status=ValidationStatus.PASS, feedback="OK"),
                fail,
            ],
        )
        assert result.failed_validations == [fail]

    def test_summary_includes_iteration_number_and_each_result(self):
        result = IterationResult(
            iteration=2,
            script="SELECT 1;",
            validations=[
                ValidationResult(agent_name="A", status=ValidationStatus.PASS, feedback="OK"),
                ValidationResult(agent_name="B", status=ValidationStatus.FAIL, feedback="Bad"),
            ],
        )
        text = result.summary()
        assert "Iteration 2" in text
        assert "[PASS] A: OK" in text
        assert "[FAIL] B: Bad" in text

    def test_validations_defaults_to_empty_list(self):
        result = IterationResult(iteration=1, script="x")
        assert result.validations == []
        assert result.all_passed is True


class TestLoopState:
    def test_has_correct_default_values(self, sample_config):
        state = LoopState(config=sample_config)
        assert state.max_iterations == 10
        assert state.current_iteration == 0
        assert state.history == []
        assert state.final_script is None
        assert state.success is False


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
