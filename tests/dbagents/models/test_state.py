from __future__ import annotations

from dbagnets.models import (
    PipelineResult,
    DatabaseType,
    SchemaLoopState,
    ScriptLoopState,
    TargetConfig,
)


class TestSchemaLoopState:
    def test_has_correct_default_values(self):
        state = SchemaLoopState(idea="movie database", depth=4)
        assert state.idea == "movie database"
        assert state.depth == 4
        assert state.max_iterations == 10
        assert state.current_iteration == 0
        assert state.history == []
        assert state.final_schema_json is None
        assert state.success is False


class TestScriptLoopState:
    def test_requires_target_and_has_correct_defaults(self):
        target = TargetConfig(
            db_type=DatabaseType.RELATIONAL,
            db_name="postgresql",
            db_version="16",
        )
        state = ScriptLoopState(target=target)
        assert state.target == target
        assert state.max_iterations == 10
        assert state.current_iteration == 0
        assert state.history == []
        assert state.final_script is None
        assert state.success is False


class TestPipelineResult:
    def test_stores_schema_result_and_script_results(self):
        schema = SchemaLoopState(idea="test", depth=2, success=True)
        target = TargetConfig(
            db_type=DatabaseType.RELATIONAL,
            db_name="postgresql",
            db_version="16",
        )
        script = ScriptLoopState(target=target, success=True)
        result = PipelineResult(schema_result=schema, script_results=[script])
        assert result.schema_result == schema
        assert len(result.script_results) == 1

    def test_all_succeeded_returns_true_when_all_pass(self):
        schema = SchemaLoopState(idea="test", depth=2, success=True)
        target = TargetConfig(
            db_type=DatabaseType.RELATIONAL,
            db_name="postgresql",
            db_version="16",
        )
        script = ScriptLoopState(target=target, success=True)
        result = PipelineResult(schema_result=schema, script_results=[script])
        assert result.all_succeeded is True

    def test_all_succeeded_returns_false_when_schema_fails(self):
        schema = SchemaLoopState(idea="test", depth=2, success=False)
        target = TargetConfig(
            db_type=DatabaseType.RELATIONAL,
            db_name="postgresql",
            db_version="16",
        )
        script = ScriptLoopState(target=target, success=True)
        result = PipelineResult(schema_result=schema, script_results=[script])
        assert result.all_succeeded is False

    def test_all_succeeded_returns_false_when_one_script_fails(self):
        schema = SchemaLoopState(idea="test", depth=2, success=True)
        target = TargetConfig(
            db_type=DatabaseType.RELATIONAL,
            db_name="postgresql",
            db_version="16",
        )
        script_ok = ScriptLoopState(target=target, success=True)
        script_fail = ScriptLoopState(target=target, success=False)
        result = PipelineResult(
            schema_result=schema, script_results=[script_ok, script_fail]
        )
        assert result.all_succeeded is False
