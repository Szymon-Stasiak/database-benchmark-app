from __future__ import annotations

import argparse
import logging
from unittest.mock import patch

import pytest

from dbagnets.main import setup_logging, parse_args, parse_target, main, DB_TYPE_MAP, _EXTENSIONS
from dbagnets.models import (
    DocumentEmbeddingMapping,
    PipelineResult,
    DatabaseType,
    TargetConfig,
)
from dbagnets.models.state import SchemaLoopState, ScriptLoopState


class TestDbTypeMap:
    def test_maps_all_six_string_keys_to_enum_values(self):
        assert DB_TYPE_MAP["relational"] == DatabaseType.RELATIONAL
        assert DB_TYPE_MAP["graph"] == DatabaseType.GRAPH
        assert DB_TYPE_MAP["vector"] == DatabaseType.VECTOR
        assert DB_TYPE_MAP["document"] == DatabaseType.DOCUMENT
        assert DB_TYPE_MAP["key_value"] == DatabaseType.KEY_VALUE
        assert DB_TYPE_MAP["time_series"] == DatabaseType.TIME_SERIES


class TestSetupLogging:
    @pytest.fixture(autouse=True)
    def _reset_logger(self):
        logger = logging.getLogger("dbagnets")
        original_handlers = logger.handlers[:]
        original_level = logger.level
        yield
        logger.handlers = original_handlers
        logger.setLevel(original_level)

    def test_sets_debug_level_when_verbose_is_true(self):
        logger = logging.getLogger("dbagnets")
        logger.handlers.clear()
        setup_logging(True)
        assert logger.level == logging.DEBUG
        assert len(logger.handlers) == 1

    def test_sets_info_level_when_verbose_is_false(self):
        logger = logging.getLogger("dbagnets")
        logger.handlers.clear()
        setup_logging(False)
        assert logger.level == logging.INFO
        assert len(logger.handlers) == 1


class TestParseArgs:
    def test_parses_required_args_with_defaults(self):
        args = parse_args([
            "--target", "relational:postgresql:16",
            "--idea", "test database",
            "--depth", "4",
        ])

        assert args.target == ["relational:postgresql:16"]
        assert args.idea == "test database"
        assert args.depth == 4
        assert args.max_iterations == 10
        assert args.model == "vertex_ai/claude-sonnet-4-6"
        assert args.output_dir is None
        assert args.sequential is False
        assert args.verbose is False

    def test_parses_all_optional_args(self):
        args = parse_args([
            "--target", "graph:neo4j:5",
            "--idea", "social network",
            "--depth", "3",
            "--max-iterations", "5",
            "--model", "openai/gpt-4o",
            "--output-dir", "/tmp/out",
            "--sequential",
            "-v",
        ])

        assert args.target == ["graph:neo4j:5"]
        assert args.max_iterations == 5
        assert args.model == "openai/gpt-4o"
        assert args.output_dir == "/tmp/out"
        assert args.sequential is True
        assert args.verbose is True

    def test_parses_multiple_targets(self):
        args = parse_args([
            "--idea", "test",
            "--depth", "4",
            "--target", "relational:postgresql:16",
            "--target", "graph:neo4j:5",
            "--target", "document:mongodb:7",
        ])

        assert len(args.target) == 3
        assert args.target[0] == "relational:postgresql:16"
        assert args.target[1] == "graph:neo4j:5"
        assert args.target[2] == "document:mongodb:7"

    def test_target_is_required(self):
        with pytest.raises(SystemExit):
            parse_args(["--idea", "test", "--depth", "4"])


class TestParseTarget:
    def test_parses_valid_target_string(self):
        target = parse_target("relational:postgresql:16")
        assert target.db_type == DatabaseType.RELATIONAL
        assert target.db_name == "postgresql"
        assert target.db_version == "16"

    def test_raises_on_invalid_format(self):
        with pytest.raises(argparse.ArgumentTypeError, match="Invalid target format"):
            parse_target("invalid")

    def test_raises_on_unknown_db_type(self):
        with pytest.raises(argparse.ArgumentTypeError, match="Unknown database type"):
            parse_target("nosql:foo:1")


class TestExtensions:
    def test_maps_all_database_types(self):
        for db_type in DatabaseType:
            assert db_type in _EXTENSIONS, f"Missing extension for {db_type}"


class TestMain:
    REQUIRED_ARGV = [
        "main.py",
        "--idea", "test",
        "--depth", "4",
        "--target", "relational:postgresql:16",
    ]

    @staticmethod
    def _make_pipeline_result(success: bool = True) -> PipelineResult:
        pg_target = TargetConfig(
            db_type=DatabaseType.RELATIONAL, db_name="postgresql", db_version="16"
        )
        schema_result = SchemaLoopState(
            idea="test",
            depth=4,
            max_iterations=10,
            current_iteration=1,
            success=True,
            final_schema_json='{"idea":"test","depth":4,"entities":[],"relationships":[]}',
        )
        script_results = [
            ScriptLoopState(
                target=pg_target,
                max_iterations=10,
                current_iteration=1,
                success=success,
                final_script="CREATE TABLE test;",
            ),
        ]
        return PipelineResult(
            schema_result=schema_result,
            script_results=script_results,
        )

    @patch("dbagnets.main.load_dotenv")
    @patch("dbagnets.main.PipelineOrchestrator")
    def test_returns_0_on_success(self, mock_orch_cls, mock_dotenv, capsys):
        mock_orch_cls.return_value.run.return_value = self._make_pipeline_result()

        with patch("sys.argv", self.REQUIRED_ARGV):
            result = main()

        assert result == 0
        mock_orch_cls.return_value.run.assert_called_once()

    @patch("dbagnets.main.load_dotenv")
    @patch("dbagnets.main.PipelineOrchestrator")
    def test_returns_1_on_failure(self, mock_orch_cls, mock_dotenv):
        mock_orch_cls.return_value.run.return_value = self._make_pipeline_result(success=False)

        with patch("sys.argv", self.REQUIRED_ARGV):
            result = main()

        assert result == 1

    @patch("dbagnets.main.load_dotenv")
    @patch("dbagnets.main.PipelineOrchestrator")
    def test_writes_output_to_dir(self, mock_orch_cls, mock_dotenv, tmp_path):
        mock_orch_cls.return_value.run.return_value = self._make_pipeline_result()

        argv = self.REQUIRED_ARGV + ["--output-dir", str(tmp_path)]
        with patch("sys.argv", argv):
            result = main()

        assert result == 0
        assert (tmp_path / "schema.json").exists()
        assert (tmp_path / "postgresql_16.sql").exists()

    @patch("dbagnets.main.load_dotenv")
    @patch("dbagnets.main.PipelineOrchestrator")
    def test_writes_embedding_mappings_to_dir(self, mock_orch_cls, mock_dotenv, tmp_path):
        mongo_target = TargetConfig(
            db_type=DatabaseType.DOCUMENT, db_name="mongodb", db_version="7.0"
        )
        mappings = [
            DocumentEmbeddingMapping(entity_name="movies", is_embedded=False),
            DocumentEmbeddingMapping(
                entity_name="reviews", is_embedded=True,
                parent_entity="movies", field_name="reviews",
            ),
        ]
        result = PipelineResult(
            schema_result=SchemaLoopState(
                idea="test", depth=4, success=True,
                final_schema_json='{"idea":"test","depth":4,"entities":[],"relationships":[]}',
            ),
            script_results=[
                ScriptLoopState(
                    target=mongo_target, success=True,
                    final_script="db.createCollection('movies');",
                    embedding_mappings=mappings,
                ),
            ],
        )
        mock_orch_cls.return_value.run.return_value = result

        argv = [
            "main.py", "--idea", "test", "--depth", "4",
            "--target", "document:mongodb:7.0",
            "--output-dir", str(tmp_path),
        ]
        with patch("sys.argv", argv):
            exit_code = main()

        assert exit_code == 0
        mappings_path = tmp_path / "mongodb_7.0_mappings.json"
        assert mappings_path.exists()
        import json
        data = json.loads(mappings_path.read_text())
        assert len(data) == 2
        assert data[1]["is_embedded"] is True
        assert data[1]["parent_entity"] == "movies"

    @patch("dbagnets.main.load_dotenv")
    @patch("dbagnets.main.PipelineOrchestrator")
    def test_prints_to_stdout_without_output_dir(self, mock_orch_cls, mock_dotenv, capsys):
        mock_orch_cls.return_value.run.return_value = self._make_pipeline_result()

        with patch("sys.argv", self.REQUIRED_ARGV):
            result = main()

        assert result == 0
        captured = capsys.readouterr()
        assert "CREATE TABLE test;" in captured.out

    @patch("dbagnets.main.load_dotenv")
    @patch("dbagnets.main.PipelineOrchestrator")
    def test_multiple_targets(self, mock_orch_cls, mock_dotenv):
        pg_target = TargetConfig(db_type=DatabaseType.RELATIONAL, db_name="postgresql", db_version="16")
        neo4j_target = TargetConfig(db_type=DatabaseType.GRAPH, db_name="neo4j", db_version="5")
        result = PipelineResult(
            schema_result=SchemaLoopState(
                idea="test", depth=2, success=True,
                final_schema_json='{"idea":"test","depth":2,"entities":[],"relationships":[]}',
            ),
            script_results=[
                ScriptLoopState(target=pg_target, success=True, final_script="CREATE TABLE t;"),
                ScriptLoopState(target=neo4j_target, success=True, final_script="CREATE (n:T)"),
            ],
        )
        mock_orch_cls.return_value.run.return_value = result

        with patch("sys.argv", [
            "main.py",
            "--idea", "test", "--depth", "2",
            "--target", "relational:postgresql:16",
            "--target", "graph:neo4j:5",
        ]):
            exit_code = main()

        assert exit_code == 0

    @patch("dbagnets.main.load_dotenv")
    @patch("dbagnets.main.PipelineOrchestrator")
    def test_passes_model_and_iterations_to_orchestrator(self, mock_orch_cls, mock_dotenv):
        mock_orch_cls.return_value.run.return_value = self._make_pipeline_result()

        with patch("sys.argv", [
            "main.py",
            "--idea", "test", "--depth", "4",
            "--target", "relational:postgresql:16",
            "--model", "openai/gpt-4o",
            "--max-iterations", "5",
            "--sequential",
        ]):
            main()

        mock_orch_cls.assert_called_once_with(
            model="openai/gpt-4o",
            max_iterations=5,
            parallel_validation=False,
        )
