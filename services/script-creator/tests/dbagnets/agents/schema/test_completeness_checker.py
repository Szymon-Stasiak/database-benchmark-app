from __future__ import annotations

import pytest
from unittest.mock import patch

from dbagnets.agents.schema.completeness_checker import SchemaCompletenessCheckerAgent
from dbagnets.models import ValidationResult, ValidationStatus
from dbagnets.models.enums import AbstractDataType
from dbagnets.models.schema import Attribute, Entity, LogicalSchema


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


class TestSchemaCompletenessCheckerAgent:
    def test_name_returns_schema_completeness_checker(self):
        agent = SchemaCompletenessCheckerAgent("test-model")
        assert agent.name == "SchemaCompletenessChecker"

    def test_role_description_is_not_empty(self):
        agent = SchemaCompletenessCheckerAgent("test-model")
        assert len(agent.role_description) > 0


class TestValidate:
    @pytest.fixture
    def agent(self):
        return SchemaCompletenessCheckerAgent("test-model")

    def test_returns_pass_when_schema_is_complete(self, agent, sample_schema):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="SchemaCompletenessChecker", status=ValidationStatus.PASS,
                feedback="Schema covers all essential entities", details="None",
            ),
        ):
            result = agent.validate(sample_schema)

        assert result.status == ValidationStatus.PASS
        assert result.feedback == "Schema covers all essential entities"
        assert result.agent_name == "SchemaCompletenessChecker"

    def test_returns_fail_when_entities_are_missing(self, agent, sample_schema):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="SchemaCompletenessChecker", status=ValidationStatus.FAIL,
                feedback="Missing critical entities: actors, directors",
                details="Present: movies. Missing: actors, directors, reviews",
            ),
        ):
            result = agent.validate(sample_schema)

        assert result.status == ValidationStatus.FAIL
        assert "Missing critical entities" in result.feedback

    def test_prompt_contains_idea(self, agent, sample_schema):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="SchemaCompletenessChecker", status=ValidationStatus.PASS,
                feedback="OK", details="None",
            ),
        ) as mock_validate:
            agent.validate(sample_schema)

        system_prompt = mock_validate.call_args.args[0]
        assert "movie management database" in system_prompt
