from __future__ import annotations

from unittest.mock import MagicMock

from orchestrator import AgentOrchestrator
from models import ValidationResult, ValidationStatus


def make_pass_result(agent_name: str) -> ValidationResult:
    return ValidationResult(
        agent_name=agent_name,
        status=ValidationStatus.PASS,
        feedback="OK",
        details="None",
    )


def make_fail_result(agent_name: str, feedback: str = "Issues found") -> ValidationResult:
    return ValidationResult(
        agent_name=agent_name,
        status=ValidationStatus.FAIL,
        feedback=feedback,
        details="Some details",
    )



class TestAgentOrchestrator:
    def test_init_uses_default_model_and_iterations(self):
        orch = AgentOrchestrator()
        assert orch.model == "vertex_ai/claude-sonnet-4-6"
        assert orch.max_iterations == 10
        assert orch.parallel_validation is True
        assert len(orch.validators) == 5

    def test_init_accepts_custom_model_iterations_and_mode(self):
        orch = AgentOrchestrator(
            model="openai/gpt-4o", max_iterations=5, parallel_validation=False
        )
        assert orch.model == "openai/gpt-4o"
        assert orch.max_iterations == 5
        assert orch.parallel_validation is False


class TestRun:
    def _setup_orchestrator(self, max_iterations=3, parallel=True):
        orch = AgentOrchestrator(max_iterations=max_iterations, parallel_validation=parallel)
        orch.generator = MagicMock()
        orch.generator.generate.return_value = "CREATE TABLE t (id INT);"
        return orch

    def test_returns_success_when_all_validators_pass_on_first_iteration(
        self, sample_config
    ):
        orch = self._setup_orchestrator()
        for v in orch.validators:
            v.validate = MagicMock(return_value=make_pass_result(v.name))

        state = orch.run(sample_config)

        assert state.success is True
        assert state.final_script == "CREATE TABLE t (id INT);"
        assert len(state.history) == 1
        assert state.current_iteration == 1
        orch.generator.generate.assert_called_once()

    def test_retries_with_feedback_and_succeeds_on_second_iteration(
        self, sample_config
    ):
        orch = self._setup_orchestrator()
        for v in orch.validators:
            v.validate = MagicMock(
                side_effect=[make_fail_result(v.name), make_pass_result(v.name)]
            )

        state = orch.run(sample_config)

        assert state.success is True
        assert len(state.history) == 2
        assert orch.generator.generate.call_count == 2

    def test_returns_failure_when_all_iterations_exhausted(
        self, sample_config
    ):
        orch = self._setup_orchestrator(max_iterations=2)
        for v in orch.validators:
            v.validate = MagicMock(return_value=make_fail_result(v.name))

        state = orch.run(sample_config)

        assert state.success is False
        assert len(state.history) == 2
        assert state.final_script == "CREATE TABLE t (id INT);"

    def test_returns_failure_with_no_history_when_max_iterations_is_zero(
        self, sample_config
    ):
        orch = AgentOrchestrator(max_iterations=0)

        state = orch.run(sample_config)

        assert state.success is False
        assert state.final_script is None
        assert len(state.history) == 0


class TestRunValidatorsSequential:
    def test_calls_each_validator_once_in_sequential_mode(self, sample_config):
        orch = AgentOrchestrator(max_iterations=1, parallel_validation=False)
        orch.generator = MagicMock()
        orch.generator.generate.return_value = "SELECT 1;"
        for v in orch.validators:
            v.validate = MagicMock(return_value=make_pass_result(v.name))

        state = orch.run(sample_config)

        assert state.success is True
        for v in orch.validators:
            v.validate.assert_called_once()


class TestRunValidatorsParallel:
    def test_converts_validator_exception_to_fail_result(self, sample_config):
        orch = AgentOrchestrator(max_iterations=1)
        orch.generator = MagicMock()
        orch.generator.generate.return_value = "SELECT 1;"

        orch.validators[0].validate = MagicMock(side_effect=RuntimeError("API timeout"))
        for v in orch.validators[1:]:
            v.validate = MagicMock(return_value=make_pass_result(v.name))

        state = orch.run(sample_config)

        results = state.history[0].validations
        error_results = [
            v for v in results
            if v.status == ValidationStatus.FAIL and "API timeout" in v.feedback
        ]
        assert len(error_results) == 1
