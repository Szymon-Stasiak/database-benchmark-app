from __future__ import annotations

import concurrent.futures
import logging
import time

from langgraph.graph import END, StateGraph

from dbagnets.models import (
    DatabaseConfig,
    GraphState,
    IterationResult,
    LoopState,
    ValidationResult,
    ValidationStatus,
)
from dbagnets.agents.generator import GeneratorAgent
from dbagnets.agents.syntax_checker import SyntaxCheckerAgent
from dbagnets.agents.topic_checker import TopicCheckerAgent
from dbagnets.agents.version_checker import VersionCheckerAgent
from dbagnets.agents.depth_checker import DepthCheckerAgent
from dbagnets.agents.best_practices_checker import BestPracticesCheckerAgent

logger = logging.getLogger("dbagnets")


class AgentOrchestrator:

    def __init__(
        self,
        model: str = "vertex_ai/claude-sonnet-4-6",
        max_iterations: int = 10,
        parallel_validation: bool = True,
    ):
        self.model = model
        self.max_iterations = max_iterations
        self.parallel_validation = parallel_validation

        self.generator = GeneratorAgent(model)
        self.validators = [
            SyntaxCheckerAgent(model),
            TopicCheckerAgent(model),
            VersionCheckerAgent(model),
            DepthCheckerAgent(model),
            BestPracticesCheckerAgent(model),
        ]

        self._graph = self._build_graph()

    def _build_graph(self) -> StateGraph:
        graph = StateGraph(GraphState)
        graph.add_node("generate", self._generate_node)
        graph.add_node("validate", self._validate_node)

        graph.set_entry_point("generate")
        graph.add_edge("generate", "validate")
        graph.add_conditional_edges("validate", self._should_continue)

        return graph.compile()

    def _generate_node(self, state: GraphState) -> dict:
        iteration = state["current_iteration"] + 1

        logger.info("")
        logger.info("-" * 60)
        logger.info("  ITERATION %d/%d", iteration, state["max_iterations"])
        logger.info("-" * 60)

        feedback = state["feedback"] if state["feedback"] else None
        previous_script = state["script"]

        logger.info("")
        logger.info("[Generator] Generating script...")
        gen_start = time.time()
        script = self.generator.generate(state["config"], feedback, previous_script)
        gen_elapsed = time.time() - gen_start
        logger.info(
            "[Generator] Script generated in %.1fs (%d chars, %d lines)",
            gen_elapsed, len(script), script.count("\n") + 1,
        )

        return {
            "script": script,
            "current_iteration": iteration,
        }

    def _validate_node(self, state: GraphState) -> dict:
        config = state["config"]
        script = state["script"]
        iteration = state["current_iteration"]

        logger.info("")
        logger.info(
            "[Validation] Running %d validators (%s)...",
            len(self.validators),
            "parallel" if self.parallel_validation else "sequential",
        )
        val_start = time.time()
        validations = self._run_validators(config, script)
        val_elapsed = time.time() - val_start
        logger.info("[Validation] All validators finished in %.1fs", val_elapsed)

        iteration_result = IterationResult(
            iteration=iteration,
            script=script,
            validations=validations,
        )

        passed = sum(1 for v in validations if v.passed)
        total = len(validations)

        logger.info("")
        logger.info(iteration_result.summary())
        logger.info("")
        logger.info(
            "[Iteration %d] Score: %d/%d passed",
            iteration, passed, total,
        )

        if iteration_result.all_passed:
            return {
                "history": [iteration_result],
                "feedback": [],
                "final_script": script,
                "success": True,
            }

        failed = iteration_result.failed_validations
        failed_names = [v.agent_name for v in failed]
        logger.info(
            "[Loop] %d/%d validations failed: %s",
            len(failed), total, ", ".join(failed_names),
        )

        if iteration < state["max_iterations"]:
            logger.info("[Loop] Feeding back errors to Generator for next iteration...")

        return {
            "history": [iteration_result],
            "feedback": failed,
        }

    def _should_continue(self, state: GraphState) -> str:
        if state["success"]:
            logger.info("")
            logger.info("=" * 60)
            logger.info("  SUCCESS! All validations passed.")
            logger.info(
                "  Completed in %d iteration(s)",
                state["current_iteration"],
            )
            logger.info("=" * 60)
            return END

        if state["current_iteration"] >= state["max_iterations"]:
            logger.info("")
            logger.info("=" * 60)
            logger.info("  FAILED: Exhausted %d iterations", state["max_iterations"])
            logger.info("=" * 60)
            return END

        return "generate"

    def run(self, config: DatabaseConfig) -> LoopState:
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

        if self.max_iterations == 0:
            return LoopState(config=config, max_iterations=0)

        initial_state: GraphState = {
            "config": config,
            "max_iterations": self.max_iterations,
            "current_iteration": 0,
            "script": None,
            "feedback": [],
            "history": [],
            "final_script": None,
            "success": False,
        }

        final_state = self._graph.invoke(initial_state)

        total_elapsed = time.time() - total_start
        logger.info("  Total time: %.1fs", total_elapsed)

        loop_state = LoopState(
            config=config,
            max_iterations=self.max_iterations,
            current_iteration=final_state["current_iteration"],
            history=final_state["history"],
            final_script=final_state["final_script"],
            success=final_state["success"],
        )

        if not loop_state.success and loop_state.history:
            last = loop_state.history[-1]
            loop_state.final_script = last.script
            passed_count = sum(1 for v in last.validations if v.passed)
            logger.info("  Best result: %d/%d validations passed", passed_count, len(last.validations))

        return loop_state

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
