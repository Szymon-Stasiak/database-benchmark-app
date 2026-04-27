from __future__ import annotations

import pytest
from unittest.mock import patch

from agents.syntax_checker import SyntaxCheckerAgent
from models import ValidationResult, ValidationStatus


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
            result = agent.validate(sample_config, "CREATE TABLE t (id INT);")

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
            result = agent.validate(sample_config, "CREATE TABLE t (id INT)")

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
            agent.validate(sample_config, "SELECT 1;")

        system_prompt = mock_validate.call_args.args[0]
        user_prompt = mock_validate.call_args.args[1]
        assert "postgresql" in system_prompt
        assert "16" in system_prompt
        assert "SELECT 1;" in user_prompt
