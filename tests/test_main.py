from __future__ import annotations

import logging
from unittest.mock import patch

import pytest

from main import setup_logging, parse_args, main, DB_TYPE_MAP
from models import DatabaseType, LoopState


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
    def test_parses_all_required_args_with_correct_defaults(self):
        with patch("sys.argv", [
            "main.py",
            "--db-type", "relational",
            "--db-name", "postgresql",
            "--db-version", "16",
            "--idea", "test database",
            "--depth", "4",
        ]):
            args = parse_args()

        assert args.db_type == "relational"
        assert args.db_name == "postgresql"
        assert args.db_version == "16"
        assert args.idea == "test database"
        assert args.depth == 4
        assert args.max_iterations == 10
        assert args.model == "claude-sonnet-4-6"
        assert args.output is None
        assert args.sequential is False
        assert args.verbose is False

    def test_parses_all_optional_args(self):
        with patch("sys.argv", [
            "main.py",
            "--db-type", "graph",
            "--db-name", "neo4j",
            "--db-version", "5",
            "--idea", "social network",
            "--depth", "3",
            "--max-iterations", "5",
            "--model", "claude-haiku-4-5-20251001",
            "--output", "out.sql",
            "--project-id", "my-project",
            "--region", "us-central1",
            "--sequential",
            "-v",
        ]):
            args = parse_args()

        assert args.db_type == "graph"
        assert args.max_iterations == 5
        assert args.model == "claude-haiku-4-5-20251001"
        assert args.output == "out.sql"
        assert args.project_id == "my-project"
        assert args.region == "us-central1"
        assert args.sequential is True
        assert args.verbose is True


class TestMain:
    REQUIRED_ARGV = [
        "main.py",
        "--db-type", "relational",
        "--db-name", "postgresql",
        "--db-version", "16",
        "--idea", "test",
        "--depth", "4",
        "--project-id", "test-project",
    ]

    def _make_state(self, sample_config, success=True, final_script="CREATE TABLE t;"):
        return LoopState(config=sample_config, success=success, final_script=final_script)

    @patch("main.load_dotenv")
    @patch("main.AnthropicVertex")
    @patch("main.AgentOrchestrator")
    def test_prints_script_to_stdout_on_success(
        self, mock_orch_cls, mock_vertex_cls, mock_dotenv, sample_config, capsys
    ):
        mock_orch_cls.return_value.run.return_value = self._make_state(sample_config)

        with patch("sys.argv", self.REQUIRED_ARGV):
            result = main()

        assert result == 0
        captured = capsys.readouterr()
        assert "CREATE TABLE t;" in captured.out

    @patch("main.load_dotenv")
    @patch("main.AnthropicVertex")
    @patch("main.AgentOrchestrator")
    def test_writes_script_to_file_when_output_flag_given(
        self, mock_orch_cls, mock_vertex_cls, mock_dotenv, sample_config, tmp_path
    ):
        output_file = tmp_path / "output.sql"
        mock_orch_cls.return_value.run.return_value = self._make_state(sample_config)

        argv = self.REQUIRED_ARGV + ["--output", str(output_file)]
        with patch("sys.argv", argv):
            result = main()

        assert result == 0
        assert output_file.read_text() == "CREATE TABLE t;"

    @patch("main.load_dotenv")
    def test_returns_1_when_project_id_is_missing(self, mock_dotenv):
        argv = [
            "main.py",
            "--db-type", "relational",
            "--db-name", "postgresql",
            "--db-version", "16",
            "--idea", "test",
            "--depth", "4",
        ]
        with patch("sys.argv", argv), patch.dict("os.environ", {}, clear=True):
            result = main()

        assert result == 1

    @patch("main.load_dotenv")
    @patch("main.AnthropicVertex")
    @patch("main.AgentOrchestrator")
    def test_returns_1_when_orchestrator_loop_fails(
        self, mock_orch_cls, mock_vertex_cls, mock_dotenv, sample_config
    ):
        mock_orch_cls.return_value.run.return_value = self._make_state(
            sample_config, success=False, final_script="partial"
        )

        with patch("sys.argv", self.REQUIRED_ARGV):
            result = main()

        assert result == 1

    @patch("main.load_dotenv")
    @patch("main.AnthropicVertex")
    @patch("main.AgentOrchestrator")
    def test_skips_output_when_final_script_is_none(
        self, mock_orch_cls, mock_vertex_cls, mock_dotenv, sample_config, capsys
    ):
        mock_orch_cls.return_value.run.return_value = self._make_state(
            sample_config, success=False, final_script=None
        )

        with patch("sys.argv", self.REQUIRED_ARGV):
            result = main()

        assert result == 1
        captured = capsys.readouterr()
        assert "CREATE TABLE" not in captured.out