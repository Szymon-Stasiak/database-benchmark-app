from __future__ import annotations

import concurrent.futures
import logging
import time

from anthropic import AnthropicVertex

from models import DatabaseConfig, IterationResult, LoopState, ValidationResult, ValidationStatus
from agents.generator import GeneratorAgent
from agents.syntax_checker import SyntaxCheckerAgent
from agents.topic_checker import TopicCheckerAgent
from agents.version_checker import VersionCheckerAgent
from agents.depth_checker import DepthCheckerAgent
from agents.best_practices_checker import BestPracticesCheckerAgent

logger = logging.getLogger("dbagnets")


class AgentOrchestrator:

    def __init__(
        self,
        client: AnthropicVertex,
        model: str = "claude-sonnet-4-6",
        max_iterations: int = 10,
        parallel_validation: bool = True,
    ):
        self.client = client
        self.model = model
        self.max_iterations = max_iterations
        self.parallel_validation = parallel_validation

        self.generator = GeneratorAgent(client, model)
        self.validators = [
            SyntaxCheckerAgent(client, model),
            TopicCheckerAgent(client, model),
            VersionCheckerAgent(client, model),
            DepthCheckerAgent(client, model),
            BestPracticesCheckerAgent(client, model),
        ]

    def run(self, config: DatabaseConfig) -> LoopState:
        state = LoopState(config=config, max_iterations=self.max_iterations)
        total_start = time.time()

        logger.info("=" * 60)
        logger.info("  DBagnets - Agent Loop")
        logger.info("=" * 60)
        logger.info("  Database: %s %s (%s)", config.db_name, config.db_version, config.db_type.value)
        logger.info("  Idea: %s", config.idea)
        logger.info("  Relationship depth: %d", config.depth)
        logger.info("  Max iterations: %d", self.max_iterations)
        logger.info("  Validation mode: %s", "parallel" if self.parallel_validation else "sequential")
        logger.info("  Model: %s", self.model)
        logger.info("=" * 60)

        feedback: list[ValidationResult] | None = None
        previous_script: str | None = None

        for iteration in range(1, self.max_iterations + 1):
            state.current_iteration = iteration
            iter_start = time.time()

            logger.info("")
            logger.info("-" * 60)
            logger.info("  ITERATION %d/%d", iteration, self.max_iterations)
            logger.info("-" * 60)

            # Step 1: Generate script
            logger.info("")
            logger.info("[Generator] Generating script...")
            gen_start = time.time()
            script = self.generator.generate(config, feedback, previous_script)
            gen_elapsed = time.time() - gen_start
            logger.info("[Generator] Script generated in %.1fs (%d chars, %d lines)",
                        gen_elapsed, len(script), script.count("\n") + 1)

            # Step 2: Validate
            logger.info("")
            logger.info("[Validation] Running %d validators (%s)...",
                        len(self.validators),
                        "parallel" if self.parallel_validation else "sequential")
            val_start = time.time()
            validations = self._run_validators(config, script)
            val_elapsed = time.time() - val_start
            logger.info("[Validation] All validators finished in %.1fs", val_elapsed)

            # Step 3: Summarize iteration
            result = IterationResult(
                iteration=iteration,
                script=script,
                validations=validations,
            )
            state.history.append(result)

            passed = sum(1 for v in validations if v.passed)
            total = len(validations)
            iter_elapsed = time.time() - iter_start

            logger.info("")
            logger.info(result.summary())
            logger.info("")
            logger.info("[Iteration %d] Score: %d/%d passed | Time: %.1fs",
                        iteration, passed, total, iter_elapsed)

            # Step 4: Check if all passed
            if result.all_passed:
                total_elapsed = time.time() - total_start
                logger.info("")
                logger.info("=" * 60)
                logger.info("  SUCCESS! All validations passed.")
                logger.info("  Completed in %d iteration(s), %.1fs total", iteration, total_elapsed)
                logger.info("=" * 60)
                state.final_script = script
                state.success = True
                return state

            # Step 5: Prepare feedback for next iteration
            failed = result.failed_validations
            failed_names = [v.agent_name for v in failed]
            logger.info("[Loop] %d/%d validations failed: %s",
                        len(failed), total, ", ".join(failed_names))

            if iteration < self.max_iterations:
                logger.info("[Loop] Feeding back errors to Generator for next iteration...")
            feedback = failed
            previous_script = script

        # Exhausted iterations
        total_elapsed = time.time() - total_start
        logger.info("")
        logger.info("=" * 60)
        logger.info("  FAILED: Exhausted %d iterations in %.1fs", self.max_iterations, total_elapsed)
        logger.info("=" * 60)

        if state.history:
            last = state.history[-1]
            state.final_script = last.script
            passed_count = sum(1 for v in last.validations if v.passed)
            logger.info("  Best result: %d/%d validations passed", passed_count, len(last.validations))

        return state

    def _run_validators(
        self, config: DatabaseConfig, script: str
    ) -> list[ValidationResult]:
        if self.parallel_validation:
            return self._run_validators_parallel(config, script)
        return self._run_validators_sequential(config, script)

    def _run_validators_sequential(
        self, config: DatabaseConfig, script: str
    ) -> list[ValidationResult]:
        results: list[ValidationResult] = []
        for validator in self.validators:
            logger.info("  [%s] Checking...", validator.name)
            start = time.time()
            result = validator.validate(config, script)
            elapsed = time.time() - start
            icon = "PASS" if result.passed else "FAIL"
            logger.info("  [%s] [%s] (%.1fs)", validator.name, icon, elapsed)
            results.append(result)
        return results

    def _run_validators_parallel(
        self, config: DatabaseConfig, script: str
    ) -> list[ValidationResult]:
        results: list[ValidationResult] = []

        with concurrent.futures.ThreadPoolExecutor(max_workers=len(self.validators)) as executor:
            future_to_validator = {
                executor.submit(validator.validate, config, script): validator
                for validator in self.validators
            }

            for future in concurrent.futures.as_completed(future_to_validator):
                validator = future_to_validator[future]
                try:
                    result = future.result()
                    icon = "PASS" if result.passed else "FAIL"
                    logger.info("  [%s] [%s]", validator.name, icon)
                    results.append(result)
                except Exception as e:
                    logger.error("  [%s] [ERROR] %s", validator.name, e)
                    results.append(
                        ValidationResult(
                            agent_name=validator.name,
                            status=ValidationStatus.FAIL,
                            feedback=f"Validator error: {e}",
                            details=str(e),
                        )
                    )

        return results