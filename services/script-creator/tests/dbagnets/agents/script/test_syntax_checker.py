from __future__ import annotations

import pytest
from unittest.mock import patch

from dbagnets.agents.script.syntax_checker import SyntaxCheckerAgent
from dbagnets.models import ValidationResult, ValidationStatus
from dbagnets.models.config import TargetConfig
from dbagnets.models.enums import AbstractDataType
from dbagnets.models.schema import Attribute, Entity, LogicalSchema
from dbagnets.models.validation_context import ValidationContext


def _sample_schema() -> LogicalSchema:
    return LogicalSchema(
        idea="test",
        depth=1,
        entities=[Entity(name="t", attributes=[Attribute(name="id", data_type=AbstractDataType.INTEGER)])],
        relationships=[],
    )


def _ctx_from(config, script):
    return ValidationContext(
        schema=_sample_schema(),
        target=TargetConfig(
            db_type=config.db_type,
            db_name=config.db_name,
            db_version=config.db_version,
        ),
        script=script,
        idea=config.idea,
        depth=config.depth,
    )


class TestSyntaxCheckerAgent:
    def test_name_returns_syntax_checker(self):
        agent = SyntaxCheckerAgent("test-model")
        assert agent.name == "SyntaxChecker"

    def test_role_description_is_not_empty(self):
        agent = SyntaxCheckerAgent("test-model")
        assert len(agent.role_description) > 0


class TestValidate:
    @pytest.fixture
    def agent(self):
        return SyntaxCheckerAgent("test-model")

    def test_returns_pass_when_llm_reports_valid_syntax(self, agent, sample_config):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="SyntaxChecker", status=ValidationStatus.PASS,
                feedback="Syntax is correct", details="None",
            ),
        ):
            result = agent.validate(_ctx_from(sample_config, "CREATE TABLE t (id INT);"))

        assert result.status == ValidationStatus.PASS
        assert result.feedback == "Syntax is correct"
        assert result.agent_name == "SyntaxChecker"

    def test_returns_fail_when_llm_reports_syntax_errors(self, agent, sample_config):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="SyntaxChecker", status=ValidationStatus.FAIL,
                feedback="Missing semicolons", details="Line 3",
            ),
        ):
            result = agent.validate(_ctx_from(sample_config, "CREATE TABLE t (id INT)"))

        assert result.status == ValidationStatus.FAIL
        assert result.feedback == "Missing semicolons"

    def test_prompt_contains_db_name_version_and_script(self, agent, sample_config):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="SyntaxChecker", status=ValidationStatus.PASS,
                feedback="OK", details="None",
            ),
        ) as mock_validate:
            agent.validate(_ctx_from(sample_config, "SELECT 1;"))

        system_prompt = mock_validate.call_args.args[0]
        user_prompt = mock_validate.call_args.args[1]
        assert "postgresql" in system_prompt
        assert "16" in system_prompt
        assert "SELECT 1;" in user_prompt
