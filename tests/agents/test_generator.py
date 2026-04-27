from __future__ import annotations

from unittest.mock import MagicMock, patch

from agents.generator import GeneratorAgent
from models import GeneratedScript, ValidationResult, ValidationStatus


def make_tool_use_response(tool_input: dict, tool_name: str = "generate_script",
                           input_tokens: int = 100, output_tokens: int = 50):
    message = MagicMock()
    tool_block = MagicMock()
    tool_block.type = "tool_use"
    tool_block.name = tool_name
    tool_block.input = tool_input
    message.content = [tool_block]
    message.usage.input_tokens = input_tokens
    message.usage.output_tokens = output_tokens
    return message


class TestGeneratorAgent:
    def test_name_returns_generator(self, mock_client):
        agent = GeneratorAgent(mock_client, "test-model")
        assert agent.name == "Generator"

    def test_role_description_is_not_empty(self, mock_client):
        agent = GeneratorAgent(mock_client, "test-model")
        assert len(agent.role_description) > 0


class TestGenerate:
    def test_returns_script_from_structured_response(self, mock_client, sample_config):
        mock_client.messages.create.return_value = make_tool_use_response(
            {"script": "CREATE TABLE movies (id INT);"}
        )
        agent = GeneratorAgent(mock_client, "test-model")

        result = agent.generate(sample_config)

        assert result == "CREATE TABLE movies (id INT);"
        mock_client.messages.create.assert_called_once()

    def test_passes_failed_feedback_and_previous_script_when_regenerating(
        self, mock_client, sample_config
    ):
        mock_client.messages.create.return_value = make_tool_use_response(
            {"script": "CREATE TABLE movies (id SERIAL PRIMARY KEY);"}
        )
        agent = GeneratorAgent(mock_client, "test-model")
        feedback = [
            ValidationResult(agent_name="SyntaxChecker", status=ValidationStatus.FAIL, feedback="Missing PK"),
            ValidationResult(agent_name="TopicChecker", status=ValidationStatus.PASS, feedback="OK"),
        ]

        result = agent.generate(sample_config, feedback, "CREATE TABLE movies (id INT);")

        assert result == "CREATE TABLE movies (id SERIAL PRIMARY KEY);"


class TestBuildSystemPrompt:
    def test_includes_db_name_version_depth_and_idea(self, mock_client, sample_config):
        agent = GeneratorAgent(mock_client, "test-model")
        prompt = agent._build_system_prompt(sample_config)

        assert "postgresql" in prompt
        assert "16" in prompt
        assert "4" in prompt
        assert "movie management database" in prompt
        assert "generate_script" in prompt


class TestBuildUserPrompt:
    def test_initial_prompt_contains_requirements_only(self, mock_client, sample_config):
        agent = GeneratorAgent(mock_client, "test-model")
        prompt = agent._build_user_prompt(sample_config, None, None)

        assert "Requirements:" in prompt
        assert "Generate a complete database initialization script." in prompt

    def test_refinement_prompt_includes_previous_script_and_feedback(self, mock_client, sample_config):
        agent = GeneratorAgent(mock_client, "test-model")
        feedback = [
            ValidationResult(agent_name="Checker", status=ValidationStatus.FAIL, feedback="Missing index"),
        ]

        prompt = agent._build_user_prompt(sample_config, feedback, "CREATE TABLE t (id INT);")

        assert "Previous script" in prompt
        assert "CREATE TABLE t (id INT);" in prompt
        assert "Missing index" in prompt
        assert "Checker" in prompt

    def test_returns_initial_prompt_when_feedback_given_without_previous_script(
        self, mock_client, sample_config
    ):
        agent = GeneratorAgent(mock_client, "test-model")
        feedback = [ValidationResult(agent_name="Checker", status=ValidationStatus.FAIL, feedback="Bad")]

        prompt = agent._build_user_prompt(sample_config, feedback, None)

        assert "Generate a complete database initialization script." in prompt
