from __future__ import annotations

import pytest
from unittest.mock import patch

from dbagnets.agents.script.naming_checker import NamingConsistencyCheckerAgent
from dbagnets.models import ValidationResult, ValidationStatus
from dbagnets.models.config import TargetConfig
from dbagnets.models.enums import AbstractDataType, DatabaseType, RelationshipCardinality
from dbagnets.models.schema import Attribute, Entity, LogicalSchema, Relationship


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
                    Attribute(name="full_name", data_type=AbstractDataType.STRING),
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


SAMPLE_SCRIPT = """\
CREATE TABLE movies (id SERIAL PRIMARY KEY, title VARCHAR(255) NOT NULL);
CREATE TABLE actors (id SERIAL PRIMARY KEY, full_name VARCHAR(255) NOT NULL);
"""


class TestNamingConsistencyCheckerAgent:
    def test_name_returns_naming_consistency_checker(self):
        agent = NamingConsistencyCheckerAgent("test-model")
        assert agent.name == "NamingConsistencyChecker"

    def test_role_description_is_not_empty(self):
        agent = NamingConsistencyCheckerAgent("test-model")
        assert len(agent.role_description) > 0


class TestValidate:
    @pytest.fixture
    def agent(self):
        return NamingConsistencyCheckerAgent("test-model")

    def test_returns_pass_when_names_match(
        self, agent, sample_target, sample_schema,
    ):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="NamingConsistencyChecker", status=ValidationStatus.PASS,
                feedback="All entity and attribute names match the LogicalSchema exactly.",
                details="None",
            ),
        ):
            result = agent.validate(sample_target, sample_schema, SAMPLE_SCRIPT)

        assert result.status == ValidationStatus.PASS
        assert result.agent_name == "NamingConsistencyChecker"

    def test_returns_fail_when_names_mismatch(
        self, agent, sample_target, sample_schema,
    ):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="NamingConsistencyChecker", status=ValidationStatus.FAIL,
                feedback="Entity 'actors': expected attribute 'full_name', found 'name'.",
                details="Attribute mismatch in entity 'actors'.",
                todos=["Rename column 'name' to 'full_name' in table 'actors'."],
            ),
        ):
            result = agent.validate(sample_target, sample_schema, "CREATE TABLE actors (id INT, name VARCHAR);")

        assert result.status == ValidationStatus.FAIL
        assert "full_name" in result.feedback

    def test_prompt_contains_entity_checklist(
        self, agent, sample_target, sample_schema,
    ):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="NamingConsistencyChecker", status=ValidationStatus.PASS,
                feedback="OK", details="None",
            ),
        ) as mock_validate:
            agent.validate(sample_target, sample_schema, SAMPLE_SCRIPT)

        system_prompt = mock_validate.call_args.args[0]
        user_prompt = mock_validate.call_args.args[1]
        assert "'movies'" in system_prompt
        assert "'actors'" in system_prompt
        assert "'id'" in system_prompt
        assert "'title'" in system_prompt
        assert "'full_name'" in system_prompt
        assert "LogicalSchema" in user_prompt
        assert SAMPLE_SCRIPT in user_prompt

    def test_prompt_contains_db_specific_rules(self, agent, sample_schema):
        graph_target = TargetConfig(
            db_type=DatabaseType.GRAPH,
            db_name="neo4j",
            db_version="5.0",
        )
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="NamingConsistencyChecker", status=ValidationStatus.PASS,
                feedback="OK", details="None",
            ),
        ) as mock_validate:
            agent.validate(graph_target, sample_schema, "CREATE CONSTRAINT ...")

        system_prompt = mock_validate.call_args.args[0]
        assert "node label" in system_prompt
        assert "PascalCase" in system_prompt
