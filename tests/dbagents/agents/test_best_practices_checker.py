from __future__ import annotations

import pytest
from unittest.mock import patch

from dbagnets.agents.best_practices_checker import BestPracticesCheckerAgent
from dbagnets.models import ValidationResult, ValidationStatus


class TestBestPracticesCheckerAgent:
    def test_name_returns_best_practices_checker(self):
        agent = BestPracticesCheckerAgent("test-model")
        assert agent.name == "BestPracticesChecker"

    def test_role_description_is_not_empty(self):
        agent = BestPracticesCheckerAgent("test-model")
        assert len(agent.role_description) > 0


class TestValidate:
    @pytest.fixture
    def agent(self):
        return BestPracticesCheckerAgent("test-model")

    def test_returns_pass_when_script_follows_best_practices(self, agent, sample_config):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="BestPracticesChecker", status=ValidationStatus.PASS,
                feedback="Follows best practices", details="None",
            ),
        ):
            result = agent.validate(sample_config, "CREATE TABLE t (id INT);")

        assert result.status == ValidationStatus.PASS
        assert result.feedback == "Follows best practices"
        assert result.agent_name == "BestPracticesChecker"

    def test_returns_fail_when_script_violates_best_practices(self, agent, sample_config):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="BestPracticesChecker", status=ValidationStatus.FAIL,
                feedback="Poor naming conventions", details="camelCase used",
            ),
        ):
            result = agent.validate(sample_config, "CREATE TABLE myTable (myCol INT);")

        assert result.status == ValidationStatus.FAIL
        assert result.feedback == "Poor naming conventions"

    def test_prompt_contains_db_name_and_idea(self, agent, sample_config):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="BestPracticesChecker", status=ValidationStatus.PASS,
                feedback="OK", details="None",
            ),
        ) as mock_validate:
            agent.validate(sample_config, "SELECT 1;")

        system_prompt = mock_validate.call_args.args[0]
        user_prompt = mock_validate.call_args.args[1]
        assert "postgresql" in system_prompt
        assert "postgresql" in user_prompt
        assert "movie management database" in user_prompt
