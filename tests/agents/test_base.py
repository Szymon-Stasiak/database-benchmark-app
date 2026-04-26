from __future__ import annotations

from agents.base import BaseAgent
from helpers import make_llm_response


class _ConcreteAgent(BaseAgent):
    @property
    def name(self) -> str:
        return "ConcreteTestAgent"

    @property
    def role_description(self) -> str:
        return "A concrete agent for testing."


class TestBaseAgent:
    def test_init_stores_client_and_default_model(self, mock_client):
        agent = _ConcreteAgent(mock_client)
        assert agent.client is mock_client
        assert agent.model == "claude-sonnet-4-6"

    def test_init_accepts_custom_model(self, mock_client):
        agent = _ConcreteAgent(mock_client, model="claude-haiku-4-5-20251001")
        assert agent.model == "claude-haiku-4-5-20251001"

    def test_abstract_name_body_returns_none(self, mock_client):
        agent = _ConcreteAgent(mock_client)
        assert BaseAgent.name.fget(agent) is None

    def test_abstract_role_description_body_returns_none(self, mock_client):
        agent = _ConcreteAgent(mock_client)
        assert BaseAgent.role_description.fget(agent) is None


class TestCallLlm:
    def test_sends_correct_params_to_vertex_api(self, mock_client):
        mock_client.messages.create.return_value = make_llm_response("response")
        agent = _ConcreteAgent(mock_client)

        agent._call_llm("system prompt", "user prompt")

        mock_client.messages.create.assert_called_once_with(
            model="claude-sonnet-4-6",
            max_tokens=8192,
            system="system prompt",
            messages=[{"role": "user", "content": "user prompt"}],
        )

    def test_returns_text_from_llm_response(self, mock_client):
        mock_client.messages.create.return_value = make_llm_response("hello world")
        agent = _ConcreteAgent(mock_client)

        result = agent._call_llm("s", "u")

        assert result == "hello world"

    def test_passes_custom_max_tokens_to_api(self, mock_client):
        mock_client.messages.create.return_value = make_llm_response("ok")
        agent = _ConcreteAgent(mock_client)

        agent._call_llm("s", "u", max_tokens=1024)

        assert mock_client.messages.create.call_args.kwargs["max_tokens"] == 1024


class TestBuildDbContext:
    def test_includes_all_config_fields_in_output(self, mock_client, sample_config):
        agent = _ConcreteAgent(mock_client)
        ctx = agent._build_db_context(sample_config)

        assert "relational" in ctx
        assert "postgresql" in ctx
        assert "16" in ctx
        assert "movie management database" in ctx
        assert "4" in ctx