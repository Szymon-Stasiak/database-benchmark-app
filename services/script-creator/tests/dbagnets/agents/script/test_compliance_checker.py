from __future__ import annotations

import pytest
from unittest.mock import patch

from dbagnets.agents.script.compliance_checker import SchemaComplianceCheckerAgent
from dbagnets.models import ValidationResult, ValidationStatus
from dbagnets.models.config import TargetConfig
from dbagnets.models.enums import AbstractDataType, DatabaseType, RelationshipCardinality
from dbagnets.models.schema import Attribute, Entity, LogicalSchema, Relationship
from dbagnets.models.validation_context import ValidationContext


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


SAMPLE_SCRIPT = """\
CREATE TABLE movies (id SERIAL PRIMARY KEY);
CREATE TABLE actors (id SERIAL PRIMARY KEY);
CREATE TABLE actors_movies (actor_id INT REFERENCES actors(id), movie_id INT REFERENCES movies(id));
"""


class TestSchemaComplianceCheckerAgent:
    def test_name_returns_schema_compliance_checker(self):
        agent = SchemaComplianceCheckerAgent("test-model")
        assert agent.name == "SchemaComplianceChecker"

    def test_role_description_is_not_empty(self):
        agent = SchemaComplianceCheckerAgent("test-model")
        assert len(agent.role_description) > 0


class TestValidate:
    @pytest.fixture
    def agent(self):
        return SchemaComplianceCheckerAgent("test-model")

    def test_returns_pass_when_script_is_compliant(
        self, agent, sample_target, sample_schema,
    ):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="SchemaComplianceChecker", status=ValidationStatus.PASS,
                feedback="Script faithfully implements the LogicalSchema", details="None",
            ),
        ):
            result = agent.validate(ValidationContext(schema=sample_schema, target=sample_target, script=SAMPLE_SCRIPT))

        assert result.status == ValidationStatus.PASS
        assert result.feedback == "Script faithfully implements the LogicalSchema"
        assert result.agent_name == "SchemaComplianceChecker"

    def test_returns_fail_when_script_is_non_compliant(
        self, agent, sample_target, sample_schema,
    ):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="SchemaComplianceChecker", status=ValidationStatus.FAIL,
                feedback="Missing entity: actors table not found in script",
                details="Entity 'actors' from LogicalSchema has no corresponding table",
            ),
        ):
            result = agent.validate(ValidationContext(schema=sample_schema, target=sample_target, script="CREATE TABLE movies (id INT);"))

        assert result.status == ValidationStatus.FAIL
        assert "Missing entity" in result.feedback

    def test_prompt_contains_schema_and_script_and_type_mapping(
        self, agent, sample_target, sample_schema,
    ):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="SchemaComplianceChecker", status=ValidationStatus.PASS,
                feedback="OK", details="None",
            ),
        ) as mock_validate:
            agent.validate(ValidationContext(schema=sample_schema, target=sample_target, script=SAMPLE_SCRIPT))

        system_prompt = mock_validate.call_args.args[0]
        user_prompt = mock_validate.call_args.args[1]
        assert "postgresql" in system_prompt
        assert "relational" in system_prompt
        assert "LogicalSchema" in user_prompt
        assert SAMPLE_SCRIPT in user_prompt
