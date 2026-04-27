from __future__ import annotations

import pytest
from unittest.mock import patch

from agents.version_checker import VersionCheckerAgent
from models import ValidationResult, ValidationStatus


class TestVersionCheckerAgent:
    def test_name_returns_version_checker(self):
        agent = VersionCheckerAgent("test-model")
        assert agent.name == "VersionChecker"

    def test_role_description_is_not_empty(self):
        agent = VersionCheckerAgent("test-model")
        assert len(agent.role_description) > 0


class TestValidate:
    @pytest.fixture
    def agent(self):
        return VersionCheckerAgent("test-model")

    def test_returns_pass_when_script_is_version_compatible(self, agent, sample_config):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="VersionChecker", status=ValidationStatus.PASS,
                feedback="All features available", details="None",
            ),
        ):
            result = agent.validate(sample_config, "CREATE TABLE t (id INT);")

        assert result.status == ValidationStatus.PASS
        assert result.feedback == "All features available"
        assert result.agent_name == "VersionChecker"

    def test_returns_fail_when_script_uses_incompatible_features(self, agent, sample_config):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="VersionChecker", status=ValidationStatus.FAIL,
                feedback="Uses features from newer version", details="GENERATED ALWAYS",
            ),
        ):
            result = agent.validate(sample_config, "CREATE TABLE t (id INT GENERATED ALWAYS);")

        assert result.status == ValidationStatus.FAIL
        assert result.feedback == "Uses features from newer version"

    def test_prompt_contains_db_name_and_version(self, agent, sample_config):
        with patch.object(
            agent, "_validate_with_tool_use",
            return_value=ValidationResult(
                agent_name="VersionChecker", status=ValidationStatus.PASS,
                feedback="OK", details="None",
            ),
        ) as mock_validate:
            agent.validate(sample_config, "SELECT 1;")

        system_prompt = mock_validate.call_args.args[0]
        user_prompt = mock_validate.call_args.args[1]
        assert "postgresql" in system_prompt
        assert "16" in system_prompt
        assert "postgresql" in user_prompt
        assert "16" in user_prompt
