from __future__ import annotations

import pytest
from unittest.mock import patch

from dbagnets.agents.depth_checker import DepthCheckerAgent
from dbagnets.models import ValidationResult, ValidationStatus


class TestDepthCheckerAgent:
    def test_name_returns_depth_checker(self):
        agent = DepthCheckerAgent("test-model")
        assert agent.name == "DepthChecker"

    def test_role_description_is_not_empty(self):
        agent = DepthCheckerAgent("test-model")
        assert len(agent.role_description) > 0


class TestValidate:
    @pytest.fixture
    def agent(self):
        return DepthCheckerAgent("test-model")

    def test_returns_pass_when_depth_matches_requirement(self, agent, sample_config):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="DepthChecker", status=ValidationStatus.PASS,
                feedback="Depth is 4 as required", details="None",
            ),
        ):
            result = agent.validate(sample_config, "CREATE TABLE t (id INT);")

        assert result.status == ValidationStatus.PASS
        assert result.feedback == "Depth is 4 as required"
        assert result.agent_name == "DepthChecker"

    def test_returns_fail_when_depth_does_not_match(self, agent, sample_config):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="DepthChecker", status=ValidationStatus.FAIL,
                feedback="Depth is 2, expected 4", details="A->B->C",
            ),
        ):
            result = agent.validate(sample_config, "CREATE TABLE t (id INT);")

        assert result.status == ValidationStatus.FAIL
        assert result.feedback == "Depth is 2, expected 4"

    def test_prompt_contains_required_depth(self, agent, sample_config):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="DepthChecker", status=ValidationStatus.PASS,
                feedback="OK", details="None",
            ),
        ) as mock_validate:
            agent.validate(sample_config, "SELECT 1;")

        system_prompt = mock_validate.call_args.args[0]
        user_prompt = mock_validate.call_args.args[1]
        assert "4" in system_prompt
        assert "4" in user_prompt
