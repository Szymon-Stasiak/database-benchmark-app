from __future__ import annotations

from unittest.mock import MagicMock

import pytest

from agents.base import BaseAgent, flatten_json_schema
from models import ValidationResult, ValidationStatus, ValidatorResponse


def make_llm_response(text: str, input_tokens: int = 100, output_tokens: int = 50):
    message = MagicMock()
    content_block = MagicMock()
    content_block.text = text
    message.content = [content_block]
    message.usage.input_tokens = input_tokens
    message.usage.output_tokens = output_tokens
    return message


def make_tool_use_response(tool_input: dict, tool_name: str = "validate",
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


class _ConcreteAgent(BaseAgent):
    @property
    def name(self) -> str:
        return "ConcreteTestAgent"

    @property
    def role_description(self) -> str:
        return "A concrete agent for testing."


class TestBaseAgent:
    def test_init_stores_client_and_default_model(self, mock_client):
        agent = _ConcreteAgent(mock_client)
        assert agent.client is mock_client
        assert agent.model == "claude-sonnet-4-6"

    def test_init_accepts_custom_model(self, mock_client):
        agent = _ConcreteAgent(mock_client, model="claude-haiku-4-5-20251001")
        assert agent.model == "claude-haiku-4-5-20251001"

    def test_abstract_name_body_returns_none(self, mock_client):
        agent = _ConcreteAgent(mock_client)
        assert BaseAgent.name.fget(agent) is None

    def test_abstract_role_description_body_returns_none(self, mock_client):
        agent = _ConcreteAgent(mock_client)
        assert BaseAgent.role_description.fget(agent) is None


class TestCallLlm:
    def test_sends_correct_params_to_vertex_api(self, mock_client):
        mock_client.messages.create.return_value = make_llm_response("response")
        agent = _ConcreteAgent(mock_client)

        agent._call_llm("system prompt", "user prompt")

        mock_client.messages.create.assert_called_once_with(
            model="claude-sonnet-4-6",
            max_tokens=8192,
            system="system prompt",
            messages=[{"role": "user", "content": "user prompt"}],
        )

    def test_returns_text_from_llm_response(self, mock_client):
        mock_client.messages.create.return_value = make_llm_response("hello world")
        agent = _ConcreteAgent(mock_client)

        result = agent._call_llm("s", "u")

        assert result == "hello world"

    def test_passes_custom_max_tokens_to_api(self, mock_client):
        mock_client.messages.create.return_value = make_llm_response("ok")
        agent = _ConcreteAgent(mock_client)

        agent._call_llm("s", "u", max_tokens=1024)

        assert mock_client.messages.create.call_args.kwargs["max_tokens"] == 1024


class TestCallLlmStructured:
    def test_passes_tools_and_tool_choice_to_api(self, mock_client):
        mock_client.messages.create.return_value = make_tool_use_response(
            {"status": "PASS", "feedback": "OK", "details": "None"}
        )
        agent = _ConcreteAgent(mock_client)

        agent._call_llm_structured("sys", "user", ValidatorResponse, "validate")

        call_kwargs = mock_client.messages.create.call_args.kwargs
        assert "tools" in call_kwargs
        assert call_kwargs["tool_choice"] == {"type": "tool", "name": "validate"}

    def test_returns_validated_pydantic_model(self, mock_client):
        mock_client.messages.create.return_value = make_tool_use_response(
            {"status": "PASS", "feedback": "OK", "details": "None"}
        )
        agent = _ConcreteAgent(mock_client)

        result = agent._call_llm_structured("sys", "user", ValidatorResponse, "validate")

        assert isinstance(result, ValidatorResponse)
        assert result.status == ValidationStatus.PASS
        assert result.feedback == "OK"

    def test_raises_when_no_tool_use_block(self, mock_client):
        message = MagicMock()
        text_block = MagicMock()
        text_block.type = "text"
        message.content = [text_block]
        message.usage.input_tokens = 100
        message.usage.output_tokens = 50
        mock_client.messages.create.return_value = message
        agent = _ConcreteAgent(mock_client)

        with pytest.raises(ValueError, match="No tool_use block"):
            agent._call_llm_structured("sys", "user", ValidatorResponse, "validate")

    def test_passes_custom_max_tokens(self, mock_client):
        mock_client.messages.create.return_value = make_tool_use_response(
            {"status": "PASS", "feedback": "OK", "details": "None"}
        )
        agent = _ConcreteAgent(mock_client)

        agent._call_llm_structured("sys", "user", ValidatorResponse, "validate", max_tokens=1024)

        assert mock_client.messages.create.call_args.kwargs["max_tokens"] == 1024


class TestValidateWithToolUse:
    def test_returns_validation_result_with_agent_name(self, mock_client):
        mock_client.messages.create.return_value = make_tool_use_response(
            {"status": "PASS", "feedback": "All good", "details": "None"}
        )
        agent = _ConcreteAgent(mock_client)

        result = agent._validate_with_tool_use("sys prompt", "user prompt")

        assert isinstance(result, ValidationResult)
        assert result.agent_name == "ConcreteTestAgent"
        assert result.status == ValidationStatus.PASS
        assert result.feedback == "All good"
        assert result.details == "None"

    def test_returns_fail_result(self, mock_client):
        mock_client.messages.create.return_value = make_tool_use_response(
            {"status": "FAIL", "feedback": "Errors found", "details": "Line 5"}
        )
        agent = _ConcreteAgent(mock_client)

        result = agent._validate_with_tool_use("sys", "user")

        assert result.status == ValidationStatus.FAIL
        assert result.feedback == "Errors found"


class TestFlattenJsonSchema:
    def test_inlines_ref_from_defs(self):
        schema = {
            "title": "MyModel",
            "type": "object",
            "properties": {
                "status": {"$ref": "#/$defs/MyEnum"},
            },
            "$defs": {
                "MyEnum": {"title": "MyEnum", "type": "string", "enum": ["A", "B"]},
            },
        }
        result = flatten_json_schema(schema)
        assert result["properties"]["status"] == {"type": "string", "enum": ["A", "B"]}
        assert "$defs" not in result
        assert "title" not in result

    def test_returns_schema_unchanged_when_no_refs(self):
        schema = {
            "type": "object",
            "properties": {"name": {"type": "string"}},
        }
        result = flatten_json_schema(schema)
        assert result == {
            "type": "object",
            "properties": {"name": {"type": "string"}},
        }

    def test_handles_nested_refs(self):
        schema = {
            "title": "Outer",
            "type": "object",
            "properties": {
                "items": {
                    "type": "array",
                    "items": {"$ref": "#/$defs/Inner"},
                },
            },
            "$defs": {
                "Inner": {
                    "title": "Inner",
                    "type": "object",
                    "properties": {"val": {"type": "integer"}},
                },
            },
        }
        result = flatten_json_schema(schema)
        assert result["properties"]["items"]["items"] == {
            "type": "object",
            "properties": {"val": {"type": "integer"}},
        }


class TestBuildDbContext:
    def test_includes_all_config_fields_in_output(self, mock_client, sample_config):
        agent = _ConcreteAgent(mock_client)
        ctx = agent._build_db_context(sample_config)

        assert "relational" in ctx
        assert "postgresql" in ctx
        assert "16" in ctx
        assert "movie management database" in ctx
        assert "4" in ctx
