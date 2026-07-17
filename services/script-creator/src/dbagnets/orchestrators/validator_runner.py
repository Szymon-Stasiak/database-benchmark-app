from __future__ import annotations

import concurrent.futures
import logging
import time

from dbagnets.models.enums import ValidationStatus
from dbagnets.models.validation import ValidationResult
from dbagnets.models.validation_context import ValidationContext, Validator

logger = logging.getLogger("dbagnets")


def run_validators(
    validators: list[Validator],
    ctx: ValidationContext,
    parallel: bool,
) -> list[ValidationResult]:
    if parallel:
        return _run_parallel(validators, ctx)
    return _run_sequential(validators, ctx)


def _run_sequential(
    validators: list[Validator], ctx: ValidationContext,
) -> list[ValidationResult]:
    results: list[ValidationResult] = []
    for validator in validators:
        logger.info("  [%s] Checking...", validator.name)
        start = time.time()
        result = _safe_validate(validator, ctx)
        elapsed = time.time() - start
        logger.info(
            "  [%s] [%s] (%.1fs)",
            validator.name, "PASS" if result.passed else "FAIL", elapsed,
        )
        results.append(result)
    return results


def _run_parallel(
    validators: list[Validator], ctx: ValidationContext,
) -> list[ValidationResult]:
    results: list[ValidationResult] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(validators)) as executor:
        future_to_validator = {
            executor.submit(_safe_validate, v, ctx): v for v in validators
        }
        for future in concurrent.futures.as_completed(future_to_validator):
            validator = future_to_validator[future]
            result = future.result()
            logger.info(
                "  [%s] [%s]",
                validator.name, "PASS" if result.passed else "FAIL",
            )
            results.append(result)
    return results


def _safe_validate(validator: Validator, ctx: ValidationContext) -> ValidationResult:
    try:
        return validator.validate(ctx)
    except Exception as e:
        logger.error("  [%s] [ERROR] %s", validator.name, e)
        return ValidationResult(
            agent_name=validator.name,
            status=ValidationStatus.FAIL,
            feedback=f"Validator error: {e}",
            details=str(e),
        )