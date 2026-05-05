from __future__ import annotations

from unittest.mock import MagicMock

from dbagnets.models import DatabaseType, DocumentEmbeddingMapping, TargetConfig, ValidationResult, ValidationStatus
from dbagnets.models.enums import AbstractDataType, RelationshipCardinality
from dbagnets.models.schema import Attribute, Entity, LogicalSchema, Relationship
from dbagnets.orchestrators import ScriptOrchestrator


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


_SAMPLE_TARGET = TargetConfig(
    db_type=DatabaseType.RELATIONAL,
    db_name="postgresql",
    db_version="16",
)

_SAMPLE_SCHEMA = LogicalSchema(
    idea="test",
    depth=1,
    entities=[
        Entity(name="users", attributes=[Attribute(name="id", data_type=AbstractDataType.INTEGER)]),
        Entity(name="posts", attributes=[Attribute(name="id", data_type=AbstractDataType.INTEGER)]),
    ],
    relationships=[
        Relationship(
            name="users_posts",
            source_entity="users",
            target_entity="posts",
            cardinality=RelationshipCardinality.ONE_TO_MANY,
        ),
    ],
)


def _mock_all_validators(orch: ScriptOrchestrator, result_fn=make_pass_result):
    for v in orch.standard_validators:
        v.validate = MagicMock(return_value=result_fn(v.name))
    for v in orch.schema_validators:
        v.validate = MagicMock(return_value=result_fn(v.name))


class TestScriptOrchestrator:
    def test_init_uses_defaults(self):
        orch = ScriptOrchestrator()
        assert orch.model == "vertex_ai/claude-sonnet-4-6"
        assert orch.max_iterations == 10
        assert orch.parallel_validation is True
        assert len(orch.standard_validators) == 3
        assert len(orch.schema_validators) == 2

    def test_init_accepts_custom_values(self):
        orch = ScriptOrchestrator(
            model="openai/gpt-4o", max_iterations=5, parallel_validation=False
        )
        assert orch.model == "openai/gpt-4o"
        assert orch.max_iterations == 5
        assert orch.parallel_validation is False


class TestScriptRun:
    def _setup_orchestrator(
        self, max_iterations: int = 3, parallel: bool = True
    ) -> ScriptOrchestrator:
        orch = ScriptOrchestrator(
            max_iterations=max_iterations, parallel_validation=parallel
        )
        orch.generator = MagicMock()
        orch.generator.generate.return_value = ("CREATE TABLE test;", [])
        return orch

    def test_returns_success_when_all_pass(self):
        orch = self._setup_orchestrator()
        _mock_all_validators(orch)

        state = orch.run(_SAMPLE_TARGET, _SAMPLE_SCHEMA, "test", 1)

        assert state.success is True
        assert state.final_script == "CREATE TABLE test;"
        assert len(state.history) == 1
        assert state.current_iteration == 1
        orch.generator.generate.assert_called_once()

    def test_retries_and_succeeds(self):
        orch = self._setup_orchestrator()

        for v in orch.standard_validators:
            v.validate = MagicMock(
                side_effect=[make_fail_result(v.name), make_pass_result(v.name)]
            )
        for v in orch.schema_validators:
            v.validate = MagicMock(
                side_effect=[make_fail_result(v.name), make_pass_result(v.name)]
            )

        state = orch.run(_SAMPLE_TARGET, _SAMPLE_SCHEMA, "test", 1)

        assert state.success is True
        assert len(state.history) == 2
        assert orch.generator.generate.call_count == 2

    def test_returns_failure_when_exhausted(self):
        orch = self._setup_orchestrator(max_iterations=2)

        for v in orch.standard_validators:
            v.validate = MagicMock(return_value=make_fail_result(v.name))
        for v in orch.schema_validators:
            v.validate = MagicMock(return_value=make_fail_result(v.name))

        state = orch.run(_SAMPLE_TARGET, _SAMPLE_SCHEMA, "test", 1)

        assert state.success is False
        assert len(state.history) == 2
        assert state.final_script is not None

    def test_passes_embedding_mappings_through(self):
        mappings = [
            DocumentEmbeddingMapping(entity_name="users", is_embedded=False),
            DocumentEmbeddingMapping(
                entity_name="posts", is_embedded=True,
                parent_entity="users", field_name="posts",
            ),
        ]
        orch = self._setup_orchestrator()
        orch.generator.generate.return_value = ("db.createCollection('users');", mappings)
        _mock_all_validators(orch)

        state = orch.run(_SAMPLE_TARGET, _SAMPLE_SCHEMA, "test", 1)

        assert state.success is True
        assert len(state.embedding_mappings) == 2
        assert state.embedding_mappings[0].entity_name == "users"
        assert state.embedding_mappings[1].is_embedded is True

    def test_max_iterations_zero(self):
        orch = ScriptOrchestrator(max_iterations=0)

        state = orch.run(_SAMPLE_TARGET, _SAMPLE_SCHEMA, "test", 1)

        assert state.success is False
        assert state.final_script is None
        assert len(state.history) == 0


class TestScriptRunSequential:
    def test_calls_validators_sequentially(self):
        orch = ScriptOrchestrator(max_iterations=1, parallel_validation=False)
        orch.generator = MagicMock()
        orch.generator.generate.return_value = ("SELECT 1;", [])
        _mock_all_validators(orch)

        state = orch.run(_SAMPLE_TARGET, _SAMPLE_SCHEMA, "test", 1)

        assert state.success is True
        for v in orch.standard_validators:
            v.validate.assert_called_once()
        for v in orch.schema_validators:
            v.validate.assert_called_once()


class TestScriptRunParallel:
    def test_handles_validator_exception(self):
        orch = ScriptOrchestrator(max_iterations=1, parallel_validation=True)
        orch.generator = MagicMock()
        orch.generator.generate.return_value = ("SELECT 1;", [])

        orch.standard_validators[0].validate = MagicMock(
            side_effect=RuntimeError("API timeout")
        )
        for v in orch.standard_validators[1:]:
            v.validate = MagicMock(return_value=make_pass_result(v.name))
        for v in orch.schema_validators:
            v.validate = MagicMock(return_value=make_pass_result(v.name))

        state = orch.run(_SAMPLE_TARGET, _SAMPLE_SCHEMA, "test", 1)

        results = state.history[0].validations
        error_results = [
            v for v in results
            if v.status == ValidationStatus.FAIL and "API timeout" in v.feedback
        ]
        assert len(error_results) == 1
