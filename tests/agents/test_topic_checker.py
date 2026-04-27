from __future__ import annotations

import pytest
from unittest.mock import patch

from agents.topic_checker import TopicCheckerAgent
from models import ValidationResult, ValidationStatus


class TestTopicCheckerAgent:
    def test_name_returns_topic_checker(self, mock_client):
        agent = TopicCheckerAgent(mock_client, "test-model")
        assert agent.name == "TopicChecker"

    def test_role_description_is_not_empty(self, mock_client):
        agent = TopicCheckerAgent(mock_client, "test-model")
        assert len(agent.role_description) > 0


class TestValidate:
    @pytest.fixture
    def agent(self, mock_client):
        return TopicCheckerAgent(mock_client, "test-model")

    def test_returns_pass_when_script_matches_topic(self, agent, sample_config):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="TopicChecker", status=ValidationStatus.PASS,
                feedback="Script matches topic", details="None",
            ),
        ):
            result = agent.validate(sample_config, "CREATE TABLE movies (id INT);")

        assert result.status == ValidationStatus.PASS
        assert result.feedback == "Script matches topic"
        assert result.agent_name == "TopicChecker"

    def test_returns_fail_when_script_misses_topic(self, agent, sample_config):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="TopicChecker", status=ValidationStatus.FAIL,
                feedback="Missing key entities", details="No actors table",
            ),
        ):
            result = agent.validate(sample_config, "CREATE TABLE orders (id INT);")

        assert result.status == ValidationStatus.FAIL
        assert result.feedback == "Missing key entities"

    def test_prompt_contains_idea_and_db_type(self, agent, sample_config):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="TopicChecker", status=ValidationStatus.PASS,
                feedback="OK", details="None",
            ),
        ) as mock_validate:
            agent.validate(sample_config, "SELECT 1;")

        system_prompt = mock_validate.call_args.args[0]
        user_prompt = mock_validate.call_args.args[1]
        assert "movie management database" in system_prompt
        assert "movie management database" in user_prompt
        assert "relational" in user_prompt
