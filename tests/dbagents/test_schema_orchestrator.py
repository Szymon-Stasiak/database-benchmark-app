from __future__ import annotations

from unittest.mock import MagicMock

from dbagnets.models import ValidationResult, ValidationStatus
from dbagnets.models.enums import AbstractDataType, RelationshipCardinality
from dbagnets.models.schema import Attribute, Entity, LogicalSchema, Relationship
from dbagnets.schema_orchestrator import SchemaOrchestrator


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


_SAMPLE_SCHEMA = LogicalSchema(
    idea="test",
    depth=2,
    entities=[
        Entity(name="a", attributes=[Attribute(name="id", data_type=AbstractDataType.INTEGER)]),
        Entity(name="b", attributes=[Attribute(name="id", data_type=AbstractDataType.INTEGER)]),
        Entity(name="c", attributes=[Attribute(name="id", data_type=AbstractDataType.INTEGER)]),
    ],
    relationships=[
        Relationship(
            name="a_b",
            source_entity="a",
            target_entity="b",
            cardinality=RelationshipCardinality.ONE_TO_MANY,
        ),
        Relationship(
            name="b_c",
            source_entity="b",
            target_entity="c",
            cardinality=RelationshipCardinality.ONE_TO_MANY,
        ),
    ],
)


class TestSchemaOrchestrator:
    def test_init_uses_defaults(self):
        orch = SchemaOrchestrator()
        assert orch.model == "vertex_ai/claude-sonnet-4-6"
        assert orch.max_iterations == 10
        assert orch.parallel_validation is True
        assert orch.depth_checker is not None
        assert len(orch.llm_validators) == 3

    def test_init_accepts_custom_values(self):
        orch = SchemaOrchestrator(
            model="openai/gpt-4o", max_iterations=5, parallel_validation=False
        )
        assert orch.model == "openai/gpt-4o"
        assert orch.max_iterations == 5
        assert orch.parallel_validation is False


class TestSchemaRun:
    def _setup_orchestrator(self, max_iterations: int = 3, parallel: bool = True) -> SchemaOrchestrator:
        orch = SchemaOrchestrator(max_iterations=max_iterations, parallel_validation=parallel)
        orch.generator = MagicMock()
        orch.generator.generate.return_value = _SAMPLE_SCHEMA
        return orch

    def test_returns_success_when_all_validators_pass(self):
        orch = self._setup_orchestrator()
        orch.depth_checker = MagicMock()
        orch.depth_checker.name = "SchemaDepthChecker"
        orch.depth_checker.validate = MagicMock(return_value=make_pass_result("SchemaDepthChecker"))

        for v in orch.llm_validators:
            v.validate = MagicMock(return_value=make_pass_result(v.name))

        state = orch.run("test", 2)

        assert state.success is True
        assert state.final_schema_json is not None
        assert len(state.history) == 1
        assert state.current_iteration == 1
        orch.generator.generate.assert_called_once()

    def test_retries_on_failure_and_succeeds(self):
        orch = self._setup_orchestrator()
        orch.depth_checker = MagicMock()
        orch.depth_checker.name = "SchemaDepthChecker"
        orch.depth_checker.validate = MagicMock(return_value=make_pass_result("SchemaDepthChecker"))

        for v in orch.llm_validators:
            v.validate = MagicMock(
                side_effect=[make_fail_result(v.name), make_pass_result(v.name)]
            )

        state = orch.run("test", 2)

        assert state.success is True
        assert len(state.history) == 2
        assert orch.generator.generate.call_count == 2

    def test_returns_failure_when_iterations_exhausted(self):
        orch = self._setup_orchestrator(max_iterations=2)
        orch.depth_checker = MagicMock()
        orch.depth_checker.name = "SchemaDepthChecker"
        orch.depth_checker.validate = MagicMock(return_value=make_fail_result("SchemaDepthChecker"))

        for v in orch.llm_validators:
            v.validate = MagicMock(return_value=make_fail_result(v.name))

        state = orch.run("test", 2)

        assert state.success is False
        assert len(state.history) == 2
        assert state.current_iteration == 2

    def test_returns_early_when_max_iterations_zero(self):
        orch = SchemaOrchestrator(max_iterations=0)

        state = orch.run("test", 2)

        assert state.success is False
        assert state.final_schema_json is None
        assert len(state.history) == 0


class TestSchemaRunSequential:
    def test_calls_validators_sequentially(self):
        orch = SchemaOrchestrator(max_iterations=1, parallel_validation=False)
        orch.generator = MagicMock()
        orch.generator.generate.return_value = _SAMPLE_SCHEMA

        orch.depth_checker = MagicMock()
        orch.depth_checker.name = "SchemaDepthChecker"
        orch.depth_checker.validate = MagicMock(return_value=make_pass_result("SchemaDepthChecker"))

        for v in orch.llm_validators:
            v.validate = MagicMock(return_value=make_pass_result(v.name))

        state = orch.run("test", 2)

        assert state.success is True
        orch.depth_checker.validate.assert_called_once()
        for v in orch.llm_validators:
            v.validate.assert_called_once()


class TestSchemaRunParallel:
    def test_handles_validator_exception(self):
        orch = SchemaOrchestrator(max_iterations=1, parallel_validation=True)
        orch.generator = MagicMock()
        orch.generator.generate.return_value = _SAMPLE_SCHEMA

        orch.depth_checker = MagicMock()
        orch.depth_checker.name = "SchemaDepthChecker"
        orch.depth_checker.validate = MagicMock(return_value=make_pass_result("SchemaDepthChecker"))

        orch.llm_validators[0].validate = MagicMock(side_effect=RuntimeError("API timeout"))
        for v in orch.llm_validators[1:]:
            v.validate = MagicMock(return_value=make_pass_result(v.name))

        state = orch.run("test", 2)

        results = state.history[0].validations
        error_results = [
            v for v in results
            if v.status == ValidationStatus.FAIL and "API timeout" in v.feedback
        ]
        assert len(error_results) == 1
