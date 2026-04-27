from __future__ import annotations

import json
from unittest.mock import MagicMock, patch

from agents.generator import GeneratorAgent
from models import GeneratedScript, ValidationResult, ValidationStatus


def make_tool_call_response(tool_input: dict, tool_name: str = "generate_script",
                            prompt_tokens: int = 100, completion_tokens: int = 50):
    response = MagicMock()
    choice = MagicMock()
    tool_call = MagicMock()
    tool_call.function.name = tool_name
    tool_call.function.arguments = json.dumps(tool_input)
    choice.message.content = None
    choice.message.tool_calls = [tool_call]
    response.choices = [choice]
    response.usage.prompt_tokens = prompt_tokens
    response.usage.completion_tokens = completion_tokens
    return response


class TestGeneratorAgent:
    def test_name_returns_generator(self):
        agent = GeneratorAgent("test-model")
        assert agent.name == "Generator"

    def test_role_description_is_not_empty(self):
        agent = GeneratorAgent("test-model")
        assert len(agent.role_description) > 0


class TestGenerate:
    def test_returns_script_from_structured_response(self, sample_config):
        agent = GeneratorAgent("test-model")
        with patch(
            "agents.base.completion",
            return_value=make_tool_call_response({"script": "CREATE TABLE movies (id INT);"}),
        ):
            result = agent.generate(sample_config)

        assert result == "CREATE TABLE movies (id INT);"

    def test_passes_failed_feedback_and_previous_script_when_regenerating(self, sample_config):
        agent = GeneratorAgent("test-model")
        with patch(
            "agents.base.completion",
            return_value=make_tool_call_response(
                {"script": "CREATE TABLE movies (id SERIAL PRIMARY KEY);"}
            ),
        ):
            feedback = [
                ValidationResult(agent_name="SyntaxChecker", status=ValidationStatus.FAIL, feedback="Missing PK"),
                ValidationResult(agent_name="TopicChecker", status=ValidationStatus.PASS, feedback="OK"),
            ]
            result = agent.generate(sample_config, feedback, "CREATE TABLE movies (id INT);")

        assert result == "CREATE TABLE movies (id SERIAL PRIMARY KEY);"


class TestBuildSystemPrompt:
    def test_includes_db_name_version_depth_and_idea(self, sample_config):
        agent = GeneratorAgent("test-model")
        prompt = agent._build_system_prompt(sample_config)

        assert "postgresql" in prompt
        assert "16" in prompt
        assert "4" in prompt
        assert "movie management database" in prompt
        assert "generate_script" in prompt


class TestBuildUserPrompt:
    def test_initial_prompt_contains_requirements_only(self, sample_config):
        agent = GeneratorAgent("test-model")
        prompt = agent._build_user_prompt(sample_config, None, None)

        assert "Requirements:" in prompt
        assert "Generate a complete database initialization script." in prompt

    def test_refinement_prompt_includes_previous_script_and_feedback(self, sample_config):
        agent = GeneratorAgent("test-model")
        feedback = [
            ValidationResult(agent_name="Checker", status=ValidationStatus.FAIL, feedback="Missing index"),
        ]

        prompt = agent._build_user_prompt(sample_config, feedback, "CREATE TABLE t (id INT);")

        assert "Previous script" in prompt
        assert "CREATE TABLE t (id INT);" in prompt
        assert "Missing index" in prompt
        assert "Checker" in prompt

    def test_returns_initial_prompt_when_feedback_given_without_previous_script(self, sample_config):
        agent = GeneratorAgent("test-model")
        feedback = [ValidationResult(agent_name="Checker", status=ValidationStatus.FAIL, feedback="Bad")]

        prompt = agent._build_user_prompt(sample_config, feedback, None)

        assert "Generate a complete database initialization script." in prompt
