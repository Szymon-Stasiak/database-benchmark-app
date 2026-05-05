from __future__ import annotations

import json
from unittest.mock import MagicMock, patch

import pytest

from dbagnets.agents.script.generator import ScriptGeneratorAgent
from dbagnets.models import ValidationResult, ValidationStatus
from dbagnets.models.config import TargetConfig
from dbagnets.models.enums import AbstractDataType, DatabaseType, RelationshipCardinality
from dbagnets.models.schema import Attribute, Entity, LogicalSchema, Relationship


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


@pytest.fixture
def sample_target():
    return TargetConfig(
        db_type=DatabaseType.RELATIONAL,
        db_name="postgresql",
        db_version="16",
    )


@pytest.fixture
def sample_schema():
    return LogicalSchema(
        idea="movie management database",
        depth=2,
        entities=[
            Entity(
                name="movies",
                attributes=[
                    Attribute(name="id", data_type=AbstractDataType.INTEGER),
                    Attribute(name="title", data_type=AbstractDataType.STRING),
                ],
            ),
            Entity(
                name="actors",
                attributes=[
                    Attribute(name="id", data_type=AbstractDataType.INTEGER),
                    Attribute(name="name", data_type=AbstractDataType.STRING),
                ],
            ),
        ],
        relationships=[
            Relationship(
                name="acted_in",
                source_entity="actors",
                target_entity="movies",
                cardinality=RelationshipCardinality.MANY_TO_MANY,
            ),
        ],
    )


class TestScriptGeneratorAgent:
    def test_name_returns_script_generator(self):
        agent = ScriptGeneratorAgent("test-model")
        assert agent.name == "ScriptGenerator"

    def test_role_description_is_not_empty(self):
        agent = ScriptGeneratorAgent("test-model")
        assert len(agent.role_description) > 0


class TestGenerate:
    def test_returns_script_and_empty_mappings(self, sample_target, sample_schema):
        agent = ScriptGeneratorAgent("test-model")
        script_text = "CREATE TABLE movies (id INT PRIMARY KEY, title VARCHAR(255));"
        with patch(
            "dbagnets.agents.base.completion",
            return_value=make_tool_call_response({"script": script_text}),
        ):
            script, mappings = agent.generate(
                sample_target, sample_schema, "movie management database", 2,
            )

        assert script == script_text
        assert mappings == []

    def test_returns_embedding_mappings_when_present(self, sample_schema):
        agent = ScriptGeneratorAgent("test-model")
        target = TargetConfig(
            db_type=DatabaseType.DOCUMENT, db_name="mongodb", db_version="7.0",
        )
        response_data = {
            "script": "db.createCollection('movies');",
            "embedding_mappings": [
                {"entity_name": "movies", "is_embedded": False},
                {"entity_name": "actors", "is_embedded": True,
                 "parent_entity": "movies", "field_name": "actors"},
            ],
        }
        with patch(
            "dbagnets.agents.base.completion",
            return_value=make_tool_call_response(response_data),
        ):
            script, mappings = agent.generate(
                target, sample_schema, "movie management database", 2,
            )

        assert script == "db.createCollection('movies');"
        assert len(mappings) == 2
        assert mappings[0].entity_name == "movies"
        assert mappings[0].is_embedded is False
        assert mappings[1].entity_name == "actors"
        assert mappings[1].is_embedded is True
        assert mappings[1].parent_entity == "movies"
        assert mappings[1].field_name == "actors"

    def test_passes_feedback_and_previous_script_when_regenerating(
        self, sample_target, sample_schema,
    ):
        agent = ScriptGeneratorAgent("test-model")
        corrected_script = "CREATE TABLE movies (id SERIAL PRIMARY KEY, title VARCHAR(255));"
        with patch(
            "dbagnets.agents.base.completion",
            return_value=make_tool_call_response({"script": corrected_script}),
        ):
            feedback = [
                ValidationResult(
                    agent_name="SyntaxChecker",
                    status=ValidationStatus.FAIL,
                    feedback="Missing PK constraint",
                ),
                ValidationResult(
                    agent_name="TopicChecker",
                    status=ValidationStatus.PASS,
                    feedback="OK",
                ),
            ]
            script, mappings = agent.generate(
                sample_target,
                sample_schema,
                "movie management database",
                2,
                feedback,
                "CREATE TABLE movies (id INT, title VARCHAR(255));",
            )

        assert script == corrected_script
        assert mappings == []


class TestBuildSystemPrompt:
    def test_includes_target_db_name_version_and_depth(self, sample_target, sample_schema):
        agent = ScriptGeneratorAgent("test-model")
        prompt = agent._build_system_prompt(sample_target, sample_schema, 2)

        assert "postgresql" in prompt
        assert "16" in prompt
        assert "2" in prompt
        assert "generate_script" in prompt

    def test_includes_db_type(self, sample_target, sample_schema):
        agent = ScriptGeneratorAgent("test-model")
        prompt = agent._build_system_prompt(sample_target, sample_schema, 2)

        assert "relational" in prompt

    def test_includes_embedding_mapping_for_document_db(self, sample_schema):
        agent = ScriptGeneratorAgent("test-model")
        target = TargetConfig(
            db_type=DatabaseType.DOCUMENT, db_name="mongodb", db_version="7.0",
        )
        prompt = agent._build_system_prompt(target, sample_schema, 2)

        assert "embedding_mappings" in prompt
        assert "is_embedded" in prompt
        assert "parent_entity" in prompt

    def test_excludes_embedding_mapping_for_non_document_db(self, sample_target, sample_schema):
        agent = ScriptGeneratorAgent("test-model")
        prompt = agent._build_system_prompt(sample_target, sample_schema, 2)

        assert "embedding_mappings" not in prompt


class TestBuildUserPrompt:
    def test_initial_prompt_contains_schema_and_idea(
        self, sample_target, sample_schema,
    ):
        agent = ScriptGeneratorAgent("test-model")
        prompt = agent._build_user_prompt(
            sample_target, sample_schema, "movie management database", 2, None, None,
        )

        assert "movie management database" in prompt
        assert "postgresql" in prompt
        assert "Generate a complete database initialization script." in prompt

    def test_refinement_prompt_includes_previous_script_and_feedback(
        self, sample_target, sample_schema,
    ):
        agent = ScriptGeneratorAgent("test-model")
        feedback = [
            ValidationResult(
                agent_name="SyntaxChecker",
                status=ValidationStatus.FAIL,
                feedback="Missing semicolons",
            ),
        ]

        prompt = agent._build_user_prompt(
            sample_target,
            sample_schema,
            "movie management database",
            2,
            feedback,
            "CREATE TABLE t (id INT)",
        )

        assert "Previous script" in prompt
        assert "CREATE TABLE t (id INT)" in prompt
        assert "Missing semicolons" in prompt
        assert "SyntaxChecker" in prompt

    def test_refinement_prompt_includes_todos_when_present(
        self, sample_target, sample_schema,
    ):
        agent = ScriptGeneratorAgent("test-model")
        feedback = [
            ValidationResult(
                agent_name="BestPracticesChecker",
                status=ValidationStatus.FAIL,
                feedback="Missing native features",
                todos=["Add trigger for updated_at", "Add partial index on active users"],
            ),
        ]

        prompt = agent._build_user_prompt(
            sample_target, sample_schema, "movie management database", 2,
            feedback, "CREATE TABLE t (id INT);",
        )

        assert "TODO:" in prompt
        assert "Add trigger for updated_at" in prompt
        assert "Add partial index on active users" in prompt

    def test_refinement_prompt_includes_details_when_no_todos(
        self, sample_target, sample_schema,
    ):
        agent = ScriptGeneratorAgent("test-model")
        feedback = [
            ValidationResult(
                agent_name="DepthChecker",
                status=ValidationStatus.FAIL,
                feedback="Depth is 1, expected 2",
                details="Longest path: movies (depth=0)",
            ),
        ]

        prompt = agent._build_user_prompt(
            sample_target, sample_schema, "movie management database", 2,
            feedback, "CREATE TABLE movies (id INT);",
        )

        assert "Depth is 1, expected 2" in prompt
        assert "Details: Longest path: movies (depth=0)" in prompt

    def test_returns_initial_prompt_when_feedback_given_without_previous_script(
        self, sample_target, sample_schema,
    ):
        agent = ScriptGeneratorAgent("test-model")
        feedback = [
            ValidationResult(
                agent_name="Checker",
                status=ValidationStatus.FAIL,
                feedback="Bad",
            ),
        ]

        prompt = agent._build_user_prompt(
            sample_target, sample_schema, "movie management database", 2, feedback, None,
        )

        assert "Generate a complete database initialization script." in prompt
