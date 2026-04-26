from __future__ import annotations

import pytest
from unittest.mock import patch

from agents.topic_checker import TopicCheckerAgent
from models import ValidationStatus


class TestTopicCheckerAgent:
    @pytest.fixture
    def agent(self, mock_client):
        return TopicCheckerAgent(mock_client, "test-model")

    def test_name_returns_topic_checker(self, agent):
        assert agent.name == "TopicChecker"

    def test_role_description_is_not_empty(self, agent):
        assert len(agent.role_description) > 0


class TestValidate:
    @pytest.fixture
    def agent(self, mock_client):
        return TopicCheckerAgent(mock_client, "test-model")

    def test_returns_pass_when_script_matches_topic(self, agent, sample_config):
        with patch.object(
            agent, "_call_llm",
            return_value="STATUS: PASS\nFEEDBACK: Script matches topic\nDETAILS: None",
        ):
            result = agent.validate(sample_config, "CREATE TABLE movies (id INT);")

        assert result.status == ValidationStatus.PASS
        assert result.feedback == "Script matches topic"
        assert result.agent_name == "TopicChecker"

    def test_returns_fail_when_script_misses_topic(self, agent, sample_config):
        with patch.object(
            agent, "_call_llm",
            return_value="STATUS: FAIL\nFEEDBACK: Missing key entities\nDETAILS: No actors table",
        ):
            result = agent.validate(sample_config, "CREATE TABLE orders (id INT);")

        assert result.status == ValidationStatus.FAIL
        assert result.feedback == "Missing key entities"

    def test_prompt_contains_idea_and_db_type(self, agent, sample_config):
        with patch.object(
            agent, "_call_llm",
            return_value="STATUS: PASS\nFEEDBACK: OK\nDETAILS: None",
        ) as mock_llm:
            agent.validate(sample_config, "SELECT 1;")

        system_prompt = mock_llm.call_args.args[0]
        user_prompt = mock_llm.call_args.args[1]
        assert "movie management database" in system_prompt
        assert "movie management database" in user_prompt
        assert "relational" in user_prompt


class TestParseResult:
    @pytest.fixture
    def agent(self, mock_client):
        return TopicCheckerAgent(mock_client, "test-model")

    def test_extracts_pass_status_with_all_fields(self, agent):
        result = agent._parse_result("STATUS: PASS\nFEEDBACK: OK\nDETAILS: None")
        assert result.status == ValidationStatus.PASS
        assert result.feedback == "OK"
        assert result.details == "None"

    def test_extracts_fail_status_with_all_fields(self, agent):
        result = agent._parse_result("STATUS: FAIL\nFEEDBACK: Bad\nDETAILS: Errors")
        assert result.status == ValidationStatus.FAIL
        assert result.feedback == "Bad"
        assert result.details == "Errors"

    def test_defaults_feedback_to_ok_when_missing_and_pass(self, agent):
        result = agent._parse_result("STATUS: PASS\nDETAILS: None")
        assert result.feedback == "OK"

    def test_defaults_feedback_to_error_msg_when_missing_and_fail(self, agent):
        result = agent._parse_result("STATUS: FAIL\nDETAILS: x")
        assert result.feedback == "Failed to parse validator response."

    def test_uses_raw_output_as_details_when_details_missing(self, agent):
        raw = "STATUS: PASS\nFEEDBACK: OK"
        result = agent._parse_result(raw)
        assert result.details == raw

    def test_defaults_to_fail_when_status_missing(self, agent):
        result = agent._parse_result("unparseable output")
        assert result.status == ValidationStatus.FAIL