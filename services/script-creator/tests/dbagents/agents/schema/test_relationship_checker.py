from __future__ import annotations

import pytest
from unittest.mock import patch

from dbagnets.agents.schema.relationship_checker import SchemaRelationshipCheckerAgent
from dbagnets.models import ValidationResult, ValidationStatus
from dbagnets.models.enums import AbstractDataType, RelationshipCardinality
from dbagnets.models.schema import Attribute, Entity, LogicalSchema, Relationship


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
            Entity(
                name="actors",
                attributes=[Attribute(name="id", data_type=AbstractDataType.INTEGER)],
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


class TestSchemaRelationshipCheckerAgent:
    def test_name_returns_schema_relationship_checker(self):
        agent = SchemaRelationshipCheckerAgent("test-model")
        assert agent.name == "SchemaRelationshipChecker"

    def test_role_description_is_not_empty(self):
        agent = SchemaRelationshipCheckerAgent("test-model")
        assert len(agent.role_description) > 0


class TestValidate:
    @pytest.fixture
    def agent(self):
        return SchemaRelationshipCheckerAgent("test-model")

    def test_returns_pass_when_relationships_are_valid(self, agent, sample_schema):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="SchemaRelationshipChecker", status=ValidationStatus.PASS,
                feedback="All relationships are valid and coherent", details="None",
            ),
        ):
            result = agent.validate(sample_schema)

        assert result.status == ValidationStatus.PASS
        assert result.feedback == "All relationships are valid and coherent"
        assert result.agent_name == "SchemaRelationshipChecker"

    def test_returns_fail_when_relationships_are_invalid(self, agent, sample_schema):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="SchemaRelationshipChecker", status=ValidationStatus.FAIL,
                feedback="Relationship references non-existent entity",
                details="Relationship 'has_reviews' references 'reviews' which does not exist",
            ),
        ):
            result = agent.validate(sample_schema)

        assert result.status == ValidationStatus.FAIL
        assert "non-existent entity" in result.feedback

    def test_prompt_contains_entity_names(self, agent, sample_schema):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="SchemaRelationshipChecker", status=ValidationStatus.PASS,
                feedback="OK", details="None",
            ),
        ) as mock_validate:
            agent.validate(sample_schema)

        system_prompt = mock_validate.call_args.args[0]
        assert "movies" in system_prompt
        assert "actors" in system_prompt
