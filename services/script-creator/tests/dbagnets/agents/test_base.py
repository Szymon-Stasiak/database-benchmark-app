from __future__ import annotations

import json
from unittest.mock import MagicMock, patch

import pytest

from dbagnets.agents.base import BaseAgent, flatten_json_schema
from dbagnets.models import ValidationResult, ValidationStatus, ValidatorResponse


def make_text_response(text: str, prompt_tokens: int = 100, completion_tokens: int = 50):
    response = MagicMock()
    choice = MagicMock()
    choice.message.content = text
    choice.message.tool_calls = None
    response.choices = [choice]
    response.usage.prompt_tokens = prompt_tokens
    response.usage.completion_tokens = completion_tokens
    return response


def make_tool_call_response(tool_input: dict, tool_name: str = "validate",
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


class _ConcreteAgent(BaseAgent):
    @property
    def name(self) -> str:
        return "ConcreteTestAgent"

    @property
    def role_description(self) -> str:
        return "A concrete agent for testing."


class TestBaseAgent:
    def test_init_stores_default_model(self):
        agent = _ConcreteAgent()
        assert agent.model == "vertex_ai/claude-sonnet-4-6"

    def test_init_accepts_custom_model(self):
        agent = _ConcreteAgent(model="openai/gpt-4o")
        assert agent.model == "openai/gpt-4o"

    def test_abstract_name_body_returns_none(self):
        agent = _ConcreteAgent()
        assert BaseAgent.name.fget(agent) is None

    def test_abstract_role_description_body_returns_none(self):
        agent = _ConcreteAgent()
        assert BaseAgent.role_description.fget(agent) is None

class TestCallLlmStructured:
    def test_passes_tools_and_tool_choice(self):
        agent = _ConcreteAgent("test-model")
        with patch(
            "dbagnets.agents.base.completion",
            return_value=make_tool_call_response(
                {"status": "PASS", "feedback": "OK", "details": "None"}
            ),
        ) as mock_comp:
            agent._call_llm_structured("sys", "user", ValidatorResponse, "validate")

        call_kwargs = mock_comp.call_args.kwargs
        assert "tools" in call_kwargs
        assert call_kwargs["tools"][0]["type"] == "function"
        assert call_kwargs["tools"][0]["function"]["name"] == "validate"
        assert call_kwargs["tool_choice"] == {"type": "function", "function": {"name": "validate"}}

    def test_returns_validated_pydantic_model(self):
        agent = _ConcreteAgent("test-model")
        with patch(
            "dbagnets.agents.base.completion",
            return_value=make_tool_call_response(
                {"status": "PASS", "feedback": "OK", "details": "None"}
            ),
        ):
            result = agent._call_llm_structured("sys", "user", ValidatorResponse, "validate")

        assert isinstance(result, ValidatorResponse)
        assert result.status == ValidationStatus.PASS
        assert result.feedback == "OK"

    def test_raises_when_no_tool_calls(self):
        agent = _ConcreteAgent("test-model")
        with patch(
            "dbagnets.agents.base.completion",
            return_value=make_text_response("no tool call"),
        ):
            with pytest.raises(ValueError, match="No tool call"):
                agent._call_llm_structured("sys", "user", ValidatorResponse, "validate")

    def test_passes_custom_max_tokens(self):
        agent = _ConcreteAgent("test-model")
        with patch(
            "dbagnets.agents.base.completion",
            return_value=make_tool_call_response(
                {"status": "PASS", "feedback": "OK", "details": "None"}
            ),
        ) as mock_comp:
            agent._call_llm_structured("sys", "user", ValidatorResponse, "validate", max_tokens=1024)

        assert mock_comp.call_args.kwargs["max_tokens"] == 1024


class TestValidateWithToolUse:
    def test_returns_validation_result_with_agent_name(self):
        agent = _ConcreteAgent("test-model")
        with patch(
            "dbagnets.agents.base.completion",
            return_value=make_tool_call_response(
                {"status": "PASS", "feedback": "All good", "details": "None"}
            ),
        ):
            result = agent._validate_with_tool_use("sys prompt", "user prompt")

        assert isinstance(result, ValidationResult)
        assert result.agent_name == "ConcreteTestAgent"
        assert result.status == ValidationStatus.PASS
        assert result.feedback == "All good"
        assert result.details == "None"

    def test_returns_fail_result(self):
        agent = _ConcreteAgent("test-model")
        with patch(
            "dbagnets.agents.base.completion",
            return_value=make_tool_call_response(
                {"status": "FAIL", "feedback": "Errors found", "details": "Line 5"}
            ),
        ):
            result = agent._validate_with_tool_use("sys", "user")

        assert result.status == ValidationStatus.FAIL
        assert result.feedback == "Errors found"


class TestFormatFeedbackBlock:
    def test_formats_failed_validators_only(self):
        feedback = [
            ValidationResult(agent_name="A", status=ValidationStatus.PASS, feedback="OK"),
            ValidationResult(agent_name="B", status=ValidationStatus.FAIL, feedback="Bad syntax"),
        ]
        result = _ConcreteAgent._format_feedback_block(feedback)
        assert "A" not in result
        assert "- [B] Bad syntax" in result

    def test_includes_todos_when_present(self):
        feedback = [
            ValidationResult(
                agent_name="C", status=ValidationStatus.FAIL, feedback="Missing indexes",
                todos=["Add index on users.email", "Add index on orders.created_at"],
            ),
        ]
        result = _ConcreteAgent._format_feedback_block(feedback)
        assert "TODO:" in result
        assert "Add index on users.email" in result
        assert "Add index on orders.created_at" in result

    def test_includes_details_when_no_todos(self):
        feedback = [
            ValidationResult(
                agent_name="D", status=ValidationStatus.FAIL, feedback="Depth mismatch",
                details="Expected depth 3, got 2",
            ),
        ]
        result = _ConcreteAgent._format_feedback_block(feedback)
        assert "Details: Expected depth 3, got 2" in result

    def test_returns_empty_string_when_all_pass(self):
        feedback = [
            ValidationResult(agent_name="A", status=ValidationStatus.PASS, feedback="OK"),
            ValidationResult(agent_name="B", status=ValidationStatus.PASS, feedback="All good"),
        ]
        result = _ConcreteAgent._format_feedback_block(feedback)
        assert result == ""

    def test_returns_empty_string_for_empty_list(self):
        assert _ConcreteAgent._format_feedback_block([]) == ""


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