from __future__ import annotations

from dbagnets.models import IterationResult, ValidationResult, ValidationStatus


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

    def test_todos_defaults_to_empty_list(self):
        r = ValidationResult(agent_name="Test", status=ValidationStatus.PASS, feedback="OK")
        assert r.todos == []

    def test_todos_stored_when_provided(self):
        r = ValidationResult(
            agent_name="Test", status=ValidationStatus.FAIL, feedback="Bad",
            todos=["Fix X", "Add Y"],
        )
        assert r.todos == ["Fix X", "Add Y"]


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

    def test_summary_includes_todos_for_failed_validations(self):
        result = IterationResult(
            iteration=1,
            script="SELECT 1;",
            validations=[
                ValidationResult(
                    agent_name="Checker", status=ValidationStatus.FAIL,
                    feedback="Missing features",
                    todos=["Add index on users.email", "Add CHECK on age"],
                ),
            ],
        )
        text = result.summary()
        assert "TODO: Add index on users.email" in text
        assert "TODO: Add CHECK on age" in text
