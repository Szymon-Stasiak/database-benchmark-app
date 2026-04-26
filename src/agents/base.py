from __future__ import annotations

import logging
import time
from abc import ABC, abstractmethod

from anthropic import AnthropicVertex

from models import DatabaseConfig

logger = logging.getLogger("dbagnets")


class BaseAgent(ABC):

    def __init__(self, client: AnthropicVertex, model: str = "claude-sonnet-4-6"):
        self.client = client
        self.model = model

    @property
    @abstractmethod
    def name(self) -> str:
        ...

    @property
    @abstractmethod
    def role_description(self) -> str:
        ...

    def _call_llm(self, system_prompt: str, user_prompt: str, max_tokens: int = 8192) -> str:
        logger.debug("[%s] Sending request to %s (max_tokens=%d)", self.name, self.model, max_tokens)
        logger.debug("[%s] System prompt length: %d chars", self.name, len(system_prompt))
        logger.debug("[%s] User prompt length: %d chars", self.name, len(user_prompt))

        start = time.time()
        message = self.client.messages.create(
            model=self.model,
            max_tokens=max_tokens,
            system=system_prompt,
            messages=[{"role": "user", "content": user_prompt}],
        )
        elapsed = time.time() - start

        response_text = message.content[0].text
        input_tokens = message.usage.input_tokens
        output_tokens = message.usage.output_tokens

        logger.info(
            "[%s] LLM response in %.1fs | tokens: %d in / %d out | response: %d chars",
            self.name, elapsed, input_tokens, output_tokens, len(response_text),
        )

        return response_text

    def _build_db_context(self, config: DatabaseConfig) -> str:
        return (
            f"Database type: {config.db_type.value}\n"
            f"Engine: {config.db_name}\n"
            f"Version: {config.db_version}\n"
            f"Description/idea: {config.idea}\n"
            f"Required relationship depth: {config.depth}"
        )