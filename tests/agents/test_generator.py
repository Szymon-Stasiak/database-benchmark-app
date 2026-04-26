from __future__ import annotations

import pytest
from unittest.mock import patch

from agents.generator import GeneratorAgent
from models import ValidationResult, ValidationStatus
from helpers import make_llm_response


class TestGeneratorAgent:
    @pytest.fixture
    def agent(self, mock_client):
        return GeneratorAgent(mock_client, "test-model")

    def test_name_returns_generator(self, agent):
        assert agent.name == "Generator"

    def test_role_description_is_not_empty(self, agent):
        assert len(agent.role_description) > 0


class TestGenerate:
    @pytest.fixture
    def agent(self, mock_client):
        return GeneratorAgent(mock_client, "test-model")

    def test_returns_extracted_script_from_llm_response(self, mock_client, agent, sample_config):
        mock_client.messages.create.return_value = make_llm_response(
            "<script>CREATE TABLE movies (id INT);</script>"
        )

        result = agent.generate(sample_config)

        assert result == "CREATE TABLE movies (id INT);"
        mock_client.messages.create.assert_called_once()

    def test_passes_failed_feedback_and_previous_script_when_regenerating(
        self, mock_client, agent, sample_config
    ):
        mock_client.messages.create.return_value = make_llm_response(
            "<script>CREATE TABLE movies (id SERIAL PRIMARY KEY);</script>"
        )
        feedback = [
            ValidationResult("SyntaxChecker", ValidationStatus.FAIL, "Missing PK"),
            ValidationResult("TopicChecker", ValidationStatus.PASS, "OK"),
        ]

        result = agent.generate(sample_config, feedback, "CREATE TABLE movies (id INT);")

        assert result == "CREATE TABLE movies (id SERIAL PRIMARY KEY);"


class TestBuildSystemPrompt:
    @pytest.fixture
    def agent(self, mock_client):
        return GeneratorAgent(mock_client, "test-model")

    def test_includes_db_name_version_depth_and_idea(self, agent, sample_config):
        prompt = agent._build_system_prompt(sample_config)

        assert "postgresql" in prompt
        assert "16" in prompt
        assert "4" in prompt
        assert "movie management database" in prompt
        assert "<script>" in prompt


class TestBuildUserPrompt:
    @pytest.fixture
    def agent(self, mock_client):
        return GeneratorAgent(mock_client, "test-model")

    def test_initial_prompt_contains_requirements_only(self, agent, sample_config):
        prompt = agent._build_user_prompt(sample_config, None, None)

        assert "Requirements:" in prompt
        assert "Generate a complete database initialization script." in prompt

    def test_refinement_prompt_includes_previous_script_and_feedback(self, agent, sample_config):
        feedback = [
            ValidationResult("Checker", ValidationStatus.FAIL, "Missing index"),
        ]

        prompt = agent._build_user_prompt(sample_config, feedback, "CREATE TABLE t (id INT);")

        assert "Previous script" in prompt
        assert "CREATE TABLE t (id INT);" in prompt
        assert "Missing index" in prompt
        assert "Checker" in prompt

    def test_returns_initial_prompt_when_feedback_given_without_previous_script(
        self, agent, sample_config
    ):
        feedback = [ValidationResult("Checker", ValidationStatus.FAIL, "Bad")]

        prompt = agent._build_user_prompt(sample_config, feedback, None)

        assert "Generate a complete database initialization script." in prompt


class TestExtractScript:
    @pytest.fixture
    def agent(self, mock_client):
        return GeneratorAgent(mock_client, "test-model")

    def test_extracts_content_between_script_tags(self, agent):
        raw = "Some text <script>SELECT 1;</script> trailing"
        assert agent._extract_script(raw) == "SELECT 1;"

    def test_extracts_sql_code_block_and_strips_language_tag(self, agent):
        raw = "Here:\n```sql\nSELECT 1;\n```\nDone."
        assert agent._extract_script(raw) == "SELECT 1;"

    def test_extracts_code_block_without_language_tag(self, agent):
        raw = "```\nSELECT 1;\n```"
        assert agent._extract_script(raw) == "SELECT 1;"

    def test_strips_cypher_language_tag_from_code_block(self, agent):
        raw = "```cypher\nCREATE (n:Node);\n```"
        assert agent._extract_script(raw) == "CREATE (n:Node);"

    def test_strips_gremlin_language_tag_from_code_block(self, agent):
        raw = "```gremlin\ng.addV('person')\n```"
        assert agent._extract_script(raw) == "g.addV('person')"

    def test_strips_cql_language_tag_from_code_block(self, agent):
        raw = "```cql\nMATCH (n) RETURN n;\n```"
        assert agent._extract_script(raw) == "MATCH (n) RETURN n;"

    def test_strips_python_language_tag_from_code_block(self, agent):
        raw = "```python\nprint('hello')\n```"
        assert agent._extract_script(raw) == "print('hello')"

    def test_falls_back_to_raw_when_single_backtick_fence(self, agent):
        raw = "some text ``` more text"
        assert agent._extract_script(raw) == "some text ``` more text"

    def test_falls_back_to_stripped_raw_when_no_markers(self, agent):
        raw = "  SELECT 1;  "
        assert agent._extract_script(raw) == "SELECT 1;"