from __future__ import annotations

from unittest.mock import MagicMock, patch

from dbagnets.pipeline_orchestrator import PipelineOrchestrator
from dbagnets.models import PipelineConfig, DatabaseType, TargetConfig
from dbagnets.models.state import SchemaLoopState, ScriptLoopState


def _make_schema_success() -> SchemaLoopState:
    return SchemaLoopState(
        idea="test",
        depth=2,
        max_iterations=10,
        current_iteration=1,
        success=True,
        final_schema_json='{"idea":"test","depth":2,"entities":[],"relationships":[]}',
    )


def _make_schema_failure() -> SchemaLoopState:
    return SchemaLoopState(
        idea="test",
        depth=2,
        max_iterations=10,
        current_iteration=10,
        success=False,
    )


def _make_script_success(target: TargetConfig) -> ScriptLoopState:
    return ScriptLoopState(
        target=target,
        max_iterations=10,
        current_iteration=1,
        success=True,
        final_script="CREATE TABLE test;",
    )


def _make_script_failure(target: TargetConfig) -> ScriptLoopState:
    return ScriptLoopState(
        target=target,
        max_iterations=10,
        current_iteration=10,
        success=False,
    )


_PG_TARGET = TargetConfig(
    db_type=DatabaseType.RELATIONAL, db_name="postgresql", db_version="16"
)
_NEO4J_TARGET = TargetConfig(
    db_type=DatabaseType.GRAPH, db_name="neo4j", db_version="5"
)

_PIPELINE_CONFIG = PipelineConfig(
    idea="test",
    depth=2,
    targets=[_PG_TARGET, _NEO4J_TARGET],
)


class TestPipelineOrchestrator:
    def test_init_uses_defaults(self):
        orch = PipelineOrchestrator()
        assert orch.model == "vertex_ai/claude-sonnet-4-6"
        assert orch.max_iterations == 10
        assert orch.parallel_validation is True

    def test_init_accepts_custom_values(self):
        orch = PipelineOrchestrator(
            model="openai/gpt-4o", max_iterations=5, parallel_validation=False
        )
        assert orch.model == "openai/gpt-4o"
        assert orch.max_iterations == 5
        assert orch.parallel_validation is False


class TestPipelineRun:
    @patch("dbagnets.pipeline_orchestrator.ScriptOrchestrator")
    @patch("dbagnets.pipeline_orchestrator.SchemaOrchestrator")
    def test_returns_success_when_schema_and_all_scripts_succeed(
        self, mock_schema_cls, mock_script_cls
    ):
        mock_schema_cls.return_value.run.return_value = _make_schema_success()

        mock_script_instance = MagicMock()
        mock_script_instance.run.side_effect = [
            _make_script_success(_PG_TARGET),
            _make_script_success(_NEO4J_TARGET),
        ]
        mock_script_cls.return_value = mock_script_instance

        orch = PipelineOrchestrator()
        result = orch.run(_PIPELINE_CONFIG)

        assert result.all_succeeded is True
        assert result.schema_result.success is True
        assert len(result.script_results) == 2

    @patch("dbagnets.pipeline_orchestrator.ScriptOrchestrator")
    @patch("dbagnets.pipeline_orchestrator.SchemaOrchestrator")
    def test_returns_failure_when_schema_fails(
        self, mock_schema_cls, mock_script_cls
    ):
        mock_schema_cls.return_value.run.return_value = _make_schema_failure()

        orch = PipelineOrchestrator()
        result = orch.run(_PIPELINE_CONFIG)

        assert result.all_succeeded is False
        assert result.schema_result.success is False
        assert len(result.script_results) == 0
        mock_script_cls.return_value.run.assert_not_called()

    @patch("dbagnets.pipeline_orchestrator.ScriptOrchestrator")
    @patch("dbagnets.pipeline_orchestrator.SchemaOrchestrator")
    def test_returns_failure_when_one_script_fails(
        self, mock_schema_cls, mock_script_cls
    ):
        mock_schema_cls.return_value.run.return_value = _make_schema_success()

        mock_script_instance = MagicMock()
        mock_script_instance.run.side_effect = [
            _make_script_success(_PG_TARGET),
            _make_script_failure(_NEO4J_TARGET),
        ]
        mock_script_cls.return_value = mock_script_instance

        orch = PipelineOrchestrator()
        result = orch.run(_PIPELINE_CONFIG)

        assert result.all_succeeded is False
        assert result.schema_result.success is True
        assert len(result.script_results) == 2

    @patch("dbagnets.pipeline_orchestrator.ScriptOrchestrator")
    @patch("dbagnets.pipeline_orchestrator.SchemaOrchestrator")
    def test_handles_script_exception(
        self, mock_schema_cls, mock_script_cls
    ):
        mock_schema_cls.return_value.run.return_value = _make_schema_success()

        mock_script_instance = MagicMock()
        mock_script_instance.run.side_effect = [
            _make_script_success(_PG_TARGET),
            Exception("Network error"),
        ]
        mock_script_cls.return_value = mock_script_instance

        orch = PipelineOrchestrator()
        result = orch.run(_PIPELINE_CONFIG)

        assert len(result.script_results) == 2
        failed = [r for r in result.script_results if not r.success]
        assert len(failed) >= 1
