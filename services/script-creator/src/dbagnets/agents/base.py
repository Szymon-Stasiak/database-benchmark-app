from __future__ import annotations

import copy
import json
import logging
import time
from abc import ABC, abstractmethod
from typing import TypeVar

from litellm import completion
from pydantic import BaseModel

from dbagnets.models import ValidationResult, ValidatorResponse

logger = logging.getLogger("dbagnets")

T = TypeVar("T", bound=BaseModel)


def flatten_json_schema(schema: dict) -> object:
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

    _RESPONSE_FORMAT_RULES = """
    
RESPONSE FORMAT — MANDATORY, NO EXCEPTIONS:
- PASS:
  feedback = exactly ONE short sentence (under 30 words). Example: "The script passes all checks."
  details = "" (empty string)
  todos = [] (empty list)
  NEVER add suggestions, recommendations, "minor issues", strengths, or commentary.
  PASS means ACCEPTED. Say nothing else. No bullet points. No markdown. No sections.
- FAIL:
  feedback = exactly ONE sentence naming the core blocker (under 40 words).
  todos = list of CONCRETE changes required to pass. Each item is a specific
  instruction like "Add index on users.email for faster lookups".
  Only include items that would flip the result from FAIL to PASS.
  Do NOT include cosmetic, optional, or "nice to have" items.
  details = "" (empty string, put everything in todos instead)"""

    def _validate_with_tool_use(
        self,
        system_prompt: str,
        user_prompt: str,
    ) -> ValidationResult:
        response = self._call_llm_structured(
            system_prompt=system_prompt + self._RESPONSE_FORMAT_RULES,
            user_prompt=user_prompt,
            response_model=ValidatorResponse,
            tool_name="validate",
        )
        return ValidationResult(
            agent_name=self.name,
            status=response.status,
            feedback=response.feedback,
            details=response.details,
            todos=response.todos,
        )