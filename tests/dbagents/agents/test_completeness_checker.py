from __future__ import annotations

import pytest
from unittest.mock import patch

from dbagnets.agents.completeness_checker import CompletenessCheckerAgent
from dbagnets.models import ValidationResult, ValidationStatus


class TestCompletenessCheckerAgent:
    def test_name_returns_completeness_checker(self):
        agent = CompletenessCheckerAgent("test-model")
        assert agent.name == "CompletenessChecker"

    def test_role_description_is_not_empty(self):
        agent = CompletenessCheckerAgent("test-model")
        assert len(agent.role_description) > 0


class TestValidate:
    @pytest.fixture
    def agent(self):
        return CompletenessCheckerAgent("test-model")

    def test_returns_pass_when_schema_is_complete(self, agent, sample_config):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="CompletenessChecker", status=ValidationStatus.PASS,
                feedback="Schema covers all key entities", details="None",
            ),
        ):
            result = agent.validate(sample_config, "CREATE TABLE t (id INT);")

        assert result.status == ValidationStatus.PASS
        assert result.feedback == "Schema covers all key entities"
        assert result.agent_name == "CompletenessChecker"

    def test_returns_fail_when_entities_are_missing(self, agent, sample_config):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="CompletenessChecker", status=ValidationStatus.FAIL,
                feedback="Missing core entities: reviews, payments",
                details="Present: movies, actors. Missing: reviews, payments",
            ),
        ):
            result = agent.validate(sample_config, "CREATE TABLE movies (id INT);")

        assert result.status == ValidationStatus.FAIL
        assert "Missing core entities" in result.feedback

    def test_prompt_contains_idea_and_db_info(self, agent, sample_config):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="CompletenessChecker", status=ValidationStatus.PASS,
                feedback="OK", details="None",
            ),
        ) as mock_validate:
            agent.validate(sample_config, "SELECT 1;")

        system_prompt = mock_validate.call_args.args[0]
        user_prompt = mock_validate.call_args.args[1]
        assert "movie management database" in system_prompt
        assert "movie management database" in user_prompt
        assert "postgresql" in user_prompt
