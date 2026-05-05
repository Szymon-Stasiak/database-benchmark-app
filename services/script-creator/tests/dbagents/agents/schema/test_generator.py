from __future__ import annotations

import json
from unittest.mock import MagicMock, patch

from dbagnets.agents.schema.generator import SchemaGeneratorAgent
from dbagnets.models import ValidationResult, ValidationStatus


def make_tool_call_response(tool_input: dict, tool_name: str = "generate_schema",
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


VALID_SCHEMA_RESPONSE = {
    "depth_chain": ["users", "orders"],
    "entities": [
        {
            "name": "users",
            "description": "System users",
            "attributes": [
                {
                    "name": "id",
                    "data_type": "integer",
                    "constraints": {"is_primary_key": True, "is_nullable": False},
                },
                {
                    "name": "email",
                    "data_type": "string",
                    "constraints": {"is_unique": True, "is_nullable": False},
                },
            ],
        },
        {
            "name": "orders",
            "description": "User orders",
            "attributes": [
                {
                    "name": "id",
                    "data_type": "integer",
                    "constraints": {"is_primary_key": True, "is_nullable": False},
                },
            ],
        },
    ],
    "relationships": [
        {
            "name": "has_orders",
            "source_entity": "users",
            "target_entity": "orders",
            "cardinality": "1:N",
        },
    ],
    "data_size_hints": [
        {"entity_name": "users", "expected_row_count": 1000},
    ],
}


class TestSchemaGeneratorAgent:
    def test_name_returns_schema_generator(self):
        agent = SchemaGeneratorAgent("test-model")
        assert agent.name == "SchemaGenerator"

    def test_role_description_is_not_empty(self):
        agent = SchemaGeneratorAgent("test-model")
        assert len(agent.role_description) > 0


class TestGenerate:
    def test_returns_logical_schema_from_structured_response(self):
        agent = SchemaGeneratorAgent("test-model")
        with patch(
            "dbagnets.agents.base.completion",
            return_value=make_tool_call_response(VALID_SCHEMA_RESPONSE),
        ):
            result = agent.generate("movie management database", 4)

        assert result.idea == "movie management database"
        assert result.depth == 4
        assert len(result.entities) == 2
        assert result.entities[0].name == "users"
        assert result.entities[1].name == "orders"
        assert len(result.relationships) == 1
        assert result.relationships[0].name == "has_orders"
        assert result.relationships[0].source_entity == "users"
        assert result.relationships[0].target_entity == "orders"
        assert len(result.data_size_hints) == 1
        assert result.data_size_hints[0].entity_name == "users"
        assert result.data_size_hints[0].expected_row_count == 1000

    def test_passes_feedback_and_previous_schema_when_regenerating(self):
        agent = SchemaGeneratorAgent("test-model")
        with patch(
            "dbagnets.agents.base.completion",
            return_value=make_tool_call_response(VALID_SCHEMA_RESPONSE),
        ):
            feedback = [
                ValidationResult(
                    agent_name="SchemaDepthChecker",
                    status=ValidationStatus.FAIL,
                    feedback="Depth is 1, expected 2",
                ),
                ValidationResult(
                    agent_name="SchemaTopicChecker",
                    status=ValidationStatus.PASS,
                    feedback="OK",
                ),
            ]
            result = agent.generate(
                "movie management database", 4, feedback=feedback, previous_schema_json='{"entities": []}'
            )

        assert len(result.entities) == 2
        assert len(result.relationships) == 1


class TestBuildSystemPrompt:
    def test_includes_idea_and_depth(self):
        agent = SchemaGeneratorAgent("test-model")
        prompt = agent._build_system_prompt("movie management database", 4)

        assert "movie management database" in prompt
        assert "4" in prompt
        assert "generate_schema" in prompt

    def test_includes_rules_for_schema_design(self):
        agent = SchemaGeneratorAgent("test-model")
        prompt = agent._build_system_prompt("e-commerce platform", 3)

        assert "e-commerce platform" in prompt
        assert "3" in prompt


class TestBuildUserPrompt:
    def test_initial_prompt_contains_idea_and_depth(self):
        agent = SchemaGeneratorAgent("test-model")
        prompt = agent._build_user_prompt("movie management database", 4, None, None)

        assert "movie management database" in prompt
        assert "4" in prompt
        assert "Generate a complete logical schema." in prompt

    def test_refinement_prompt_includes_previous_schema_and_feedback(self):
        agent = SchemaGeneratorAgent("test-model")
        feedback = [
            ValidationResult(
                agent_name="SchemaDepthChecker",
                status=ValidationStatus.FAIL,
                feedback="Depth is 1, expected 4",
            ),
        ]

        prompt = agent._build_user_prompt(
            "movie management database",
            4,
            feedback,
            '{"entities": [{"name": "users"}]}',
        )

        assert "Previous schema" in prompt
        assert '{"entities": [{"name": "users"}]}' in prompt
        assert "Depth is 1, expected 4" in prompt
        assert "SchemaDepthChecker" in prompt

    def test_refinement_prompt_includes_details_when_present(self):
        agent = SchemaGeneratorAgent("test-model")
        feedback = [
            ValidationResult(
                agent_name="SchemaDepthChecker",
                status=ValidationStatus.FAIL,
                feedback="Depth is 1, expected 4",
                details="Longest path: users -> orders (depth=1)",
            ),
        ]

        prompt = agent._build_user_prompt(
            "movie management database", 4, feedback, '{"entities": []}'
        )

        assert "Depth is 1, expected 4" in prompt
        assert "Longest path: users -> orders (depth=1)" in prompt

    def test_refinement_prompt_includes_todos_when_present(self):
        agent = SchemaGeneratorAgent("test-model")
        feedback = [
            ValidationResult(
                agent_name="BestPracticesChecker",
                status=ValidationStatus.FAIL,
                feedback="Missing features",
                todos=["Add CHECK on age", "Add trigger for updated_at"],
            ),
        ]

        prompt = agent._build_user_prompt(
            "movie management database", 4, feedback, '{"entities": []}'
        )

        assert "TODO:" in prompt
        assert "Add CHECK on age" in prompt
        assert "Add trigger for updated_at" in prompt

    def test_returns_initial_prompt_when_feedback_given_without_previous_schema(self):
        agent = SchemaGeneratorAgent("test-model")
        feedback = [
            ValidationResult(
                agent_name="Checker",
                status=ValidationStatus.FAIL,
                feedback="Bad",
            ),
        ]

        prompt = agent._build_user_prompt("movie management database", 4, feedback, None)

        assert "Generate a complete logical schema." in prompt
