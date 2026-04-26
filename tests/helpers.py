from __future__ import annotations

from unittest.mock import MagicMock

from models import ValidationResult, ValidationStatus


def make_llm_response(text: str, input_tokens: int = 100, output_tokens: int = 50):
    message = MagicMock()
    content_block = MagicMock()
    content_block.text = text
    message.content = [content_block]
    message.usage.input_tokens = input_tokens
    message.usage.output_tokens = output_tokens
    return message


def make_pass_result(agent_name: str) -> ValidationResult:
    return ValidationResult(
        agent_name=agent_name,
        status=ValidationStatus.PASS,
        feedback="OK",
        details="None",
    )


def make_fail_result(agent_name: str, feedback: str = "Issues found") -> ValidationResult:
    return ValidationResult(
        agent_name=agent_name,
        status=ValidationStatus.FAIL,
        feedback=feedback,
        details="Some details",
    )