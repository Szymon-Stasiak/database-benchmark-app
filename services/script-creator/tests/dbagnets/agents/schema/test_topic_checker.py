from __future__ import annotations

import pytest
from unittest.mock import patch

from dbagnets.agents.schema.topic_checker import SchemaTopicCheckerAgent
from dbagnets.models import ValidationResult, ValidationStatus
from dbagnets.models.enums import AbstractDataType
from dbagnets.models.schema import Attribute, Entity, LogicalSchema
from dbagnets.models.validation_context import ValidationContext


@pytest.fixture
def sample_schema():
    return LogicalSchema(
        idea="movie management database",
        depth=2,
        entities=[
            Entity(
                name="movies",
                attributes=[Attribute(name="id", data_type=AbstractDataType.INTEGER)],
            ),
        ],
        relationships=[],
    )


class TestSchemaTopicCheckerAgent:
    def test_name_returns_schema_topic_checker(self):
        agent = SchemaTopicCheckerAgent("test-model")
        assert agent.name == "SchemaTopicChecker"

    def test_role_description_is_not_empty(self):
        agent = SchemaTopicCheckerAgent("test-model")
        assert len(agent.role_description) > 0


class TestValidate:
    @pytest.fixture
    def agent(self):
        return SchemaTopicCheckerAgent("test-model")

    def test_returns_pass_when_schema_matches_topic(self, agent, sample_schema):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="SchemaTopicChecker", status=ValidationStatus.PASS,
                feedback="Schema is well-aligned with the topic", details="None",
            ),
        ):
            result = agent.validate(ValidationContext(schema=sample_schema))

        assert result.status == ValidationStatus.PASS
        assert result.feedback == "Schema is well-aligned with the topic"
        assert result.agent_name == "SchemaTopicChecker"

    def test_returns_fail_when_schema_misses_topic(self, agent, sample_schema):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="SchemaTopicChecker", status=ValidationStatus.FAIL,
                feedback="Schema contains irrelevant entities", details="Entity 'rockets' is not related to movies",
            ),
        ):
            result = agent.validate(ValidationContext(schema=sample_schema))

        assert result.status == ValidationStatus.FAIL
        assert result.feedback == "Schema contains irrelevant entities"

    def test_prompt_contains_schema_idea(self, agent, sample_schema):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="SchemaTopicChecker", status=ValidationStatus.PASS,
                feedback="OK", details="None",
            ),
        ) as mock_validate:
            agent.validate(ValidationContext(schema=sample_schema))

        system_prompt = mock_validate.call_args.args[0]
        assert "movie management database" in system_prompt
