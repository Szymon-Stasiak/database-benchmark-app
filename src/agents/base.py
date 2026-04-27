from __future__ import annotations

import copy
import json
import logging
import time
from abc import ABC, abstractmethod
from typing import TypeVar

from litellm import completion
from pydantic import BaseModel

from models import DatabaseConfig, ValidationResult, ValidatorResponse

logger = logging.getLogger("dbagnets")

T = TypeVar("T", bound=BaseModel)


def flatten_json_schema(schema: dict) -> dict:
    """Inline $defs references for API compatibility."""
    defs = schema.pop("$defs", {})
    schema.pop("title", None)

    def resolve_refs(obj: object) -> object:
        if isinstance(obj, dict):
            if "$ref" in obj:
                ref_name = obj["$ref"].split("/")[-1]
                resolved = copy.deepcopy(defs[ref_name])
                resolved.pop("title", None)
                return resolve_refs(resolved)
            return {k: resolve_refs(v) for k, v in obj.items()}
        if isinstance(obj, list):
            return [resolve_refs(item) for item in obj]
        return obj

    return resolve_refs(schema)


class BaseAgent(ABC):

    def __init__(self, model: str = "vertex_ai/claude-sonnet-4-6"):
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
        response = completion(
            model=self.model,
            max_tokens=max_tokens,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
        )
        elapsed = time.time() - start

        response_text = response.choices[0].message.content
        prompt_tokens = response.usage.prompt_tokens
        completion_tokens = response.usage.completion_tokens

        logger.info(
            "[%s] LLM response in %.1fs | tokens: %d in / %d out | response: %d chars",
            self.name, elapsed, prompt_tokens, completion_tokens, len(response_text),
        )

        return response_text

    def _call_llm_structured(
        self,
        system_prompt: str,
        user_prompt: str,
        response_model: type[T],
        tool_name: str,
        max_tokens: int = 8192,
    ) -> T:
        logger.debug("[%s] Sending structured request to %s (max_tokens=%d)", self.name, self.model, max_tokens)
        logger.debug("[%s] System prompt length: %d chars", self.name, len(system_prompt))
        logger.debug("[%s] User prompt length: %d chars", self.name, len(user_prompt))
        logger.debug("[%s] Response model: %s, tool: %s", self.name, response_model.__name__, tool_name)

        tool_def = {
            "type": "function",
            "function": {
                "name": tool_name,
                "description": f"Return the result as structured JSON matching the {response_model.__name__} schema.",
                "parameters": flatten_json_schema(response_model.model_json_schema()),
            },
        }

        start = time.time()
        response = completion(
            model=self.model,
            max_tokens=max_tokens,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            tools=[tool_def],
            tool_choice={"type": "function", "function": {"name": tool_name}},
        )
        elapsed = time.time() - start

        prompt_tokens = response.usage.prompt_tokens
        completion_tokens = response.usage.completion_tokens

        tool_calls = response.choices[0].message.tool_calls
        if tool_calls:
            args = json.loads(tool_calls[0].function.arguments)
            logger.info(
                "[%s] LLM structured response in %.1fs | tokens: %d in / %d out",
                self.name, elapsed, prompt_tokens, completion_tokens,
            )
            return response_model.model_validate(args)

        raise ValueError(f"[{self.name}] No tool call found in LLM response")

    def _validate_with_tool_use(
        self,
        system_prompt: str,
        user_prompt: str,
    ) -> ValidationResult:
        response = self._call_llm_structured(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            response_model=ValidatorResponse,
            tool_name="validate",
        )
        return ValidationResult(
            agent_name=self.name,
            status=response.status,
            feedback=response.feedback,
            details=response.details,
        )

    def _build_db_context(self, config: DatabaseConfig) -> str:
        return (
            f"Database type: {config.db_type.value}\n"
            f"Engine: {config.db_name}\n"
            f"Version: {config.db_version}\n"
            f"Description/idea: {config.idea}\n"
            f"Required relationship depth: {config.depth}"
        )
