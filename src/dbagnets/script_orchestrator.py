from __future__ import annotations

import concurrent.futures
import logging
import time

from langgraph.graph import END, StateGraph

from dbagnets.agents.script.best_practices_checker import BestPracticesCheckerAgent
from dbagnets.log_context import set_log_context
from dbagnets.agents.script.depth_checker import DepthCheckerAgent
from dbagnets.agents.script.compliance_checker import SchemaComplianceCheckerAgent
from dbagnets.agents.script.generator import ScriptGeneratorAgent
from dbagnets.agents.script.syntax_checker import SyntaxCheckerAgent
from dbagnets.agents.script.version_checker import VersionCheckerAgent
from dbagnets.models import (
    DatabaseConfig,
    DatabaseType,
    IterationResult,
    ScriptGraphState,
    ScriptLoopState,
    ValidationResult,
    ValidationStatus,
)
from dbagnets.models.config import TargetConfig
from dbagnets.models.schema import LogicalSchema

logger = logging.getLogger("dbagnets")


class ScriptOrchestrator:

    def __init__(
        self,
        model: str = "vertex_ai/claude-sonnet-4-6",
        max_iterations: int = 10,
        parallel_validation: bool = True,
    ):
        self.model = model
        self.max_iterations = max_iterations
        self.parallel_validation = parallel_validation

        self.generator = ScriptGeneratorAgent(model)
        self.compliance_checker = SchemaComplianceCheckerAgent(model)
        self.standard_validators = [
            SyntaxCheckerAgent(model),
            VersionCheckerAgent(model),
            DepthCheckerAgent(model),
            BestPracticesCheckerAgent(model),
        ]

        self._graph = self._build_graph()

    def _get_validators_for_target(self, db_type: DatabaseType) -> list:
        # Graph DDL (Cypher) only defines constraints/indexes — no relationship
        # patterns — so DepthChecker cannot determine depth from the script.
        # SchemaDepthChecker already verified depth at the schema level.
        if db_type == DatabaseType.GRAPH:
            return [v for v in self.standard_validators if not isinstance(v, DepthCheckerAgent)]
        return list(self.standard_validators)

    def _build_graph(self) -> StateGraph:
        graph = StateGraph(ScriptGraphState)
        graph.add_node("generate_script", self._generate_node)
        graph.add_node("validate_script", self._validate_node)

        graph.set_entry_point("generate_script")
        graph.add_edge("generate_script", "validate_script")
        graph.add_conditional_edges("validate_script", self._should_continue)

        return graph.compile()

    def _generate_node(self, state: ScriptGraphState) -> dict:
        iteration = state["current_iteration"] + 1
        target = state["target"]
        schema = LogicalSchema.model_validate_json(state["schema_json"])

        logger.info("")
        logger.info("-" * 60)
        logger.info(
            "  [%s] SCRIPT ITERATION %d/%d",
            target.db_name, iteration, state["max_iterations"],
        )
        logger.info("-" * 60)

        feedback = state["feedback"] if state["feedback"] else None
        previous_script = state["script"]

        logger.info("")
        logger.info("[ScriptGenerator:%s] Generating script...", target.db_name)
        gen_start = time.time()
        script = self.generator.generate(
            target, schema, state["idea"], state["depth"], feedback, previous_script
        )
        gen_elapsed = time.time() - gen_start
        logger.info(
            "[ScriptGenerator:%s] Script generated in %.1fs (%d chars, %d lines)",
            target.db_name, gen_elapsed, len(script), script.count("\n") + 1,
        )

        return {
            "script": script,
            "current_iteration": iteration,
        }

    def _validate_node(self, state: ScriptGraphState) -> dict:
        target = state["target"]
        schema = LogicalSchema.model_validate_json(state["schema_json"])
        script = state["script"]
        iteration = state["current_iteration"]

        config = DatabaseConfig(
            db_type=target.db_type,
            db_name=target.db_name,
            db_version=target.db_version,
            idea=state["idea"],
            depth=state["depth"],
        )

        logger.info("")
        logger.info(
            "[%s Validation] Running validators (%s)...",
            target.db_name,
            "parallel" if self.parallel_validation else "sequential",
        )
        val_start = time.time()
        validations = self._run_validators(config, target, schema, script)
        val_elapsed = time.time() - val_start
        logger.info("[%s Validation] All validators finished in %.1fs", target.db_name, val_elapsed)

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
        logger.info("[%s Iteration %d] Score: %d/%d passed", target.db_name, iteration, passed, total)

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
            "[%s] %d/%d validations failed: %s",
            target.db_name, len(failed), total, ", ".join(failed_names),
        )

        if iteration < state["max_iterations"]:
            logger.info("[%s] Feeding back errors for next iteration...", target.db_name)

        return {
            "history": [iteration_result],
            "feedback": failed,
        }

    def _should_continue(self, state: ScriptGraphState) -> str:
        target = state["target"]

        if state["success"]:
            logger.info("")
            logger.info("=" * 60)
            logger.info("  [%s] SCRIPT SUCCESS! All validations passed.", target.db_name)
            logger.info("  Completed in %d iteration(s)", state["current_iteration"])
            logger.info("=" * 60)
            return END

        if state["current_iteration"] >= state["max_iterations"]:
            logger.info("")
            logger.info("=" * 60)
            logger.info("  [%s] SCRIPT FAILED: Exhausted %d iterations", target.db_name, state["max_iterations"])
            logger.info("=" * 60)
            return END

        return "generate_script"

    def run(
        self,
        target: TargetConfig,
        schema: LogicalSchema,
        idea: str,
        depth: int,
    ) -> ScriptLoopState:
        total_start = time.time()
        set_log_context(target.db_name)

        logger.info("=" * 60)
        logger.info("  Script Generation: %s %s", target.db_name, target.db_version)
        logger.info("=" * 60)

        if self.max_iterations == 0:
            return ScriptLoopState(target=target, max_iterations=0)

        initial_state: ScriptGraphState = {
            "target": target,
            "schema_json": schema.model_dump_json(),
            "idea": idea,
            "depth": depth,
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
        logger.info("  [%s] Total time: %.1fs", target.db_name, total_elapsed)

        loop_state = ScriptLoopState(
            target=target,
            max_iterations=self.max_iterations,
            current_iteration=final_state["current_iteration"],
            history=final_state["history"],
            final_script=final_state["final_script"],
            success=final_state["success"],
        )

        if not loop_state.success and loop_state.history:
            last = loop_state.history[-1]
            loop_state.final_script = last.script

        return loop_state

    def _run_validators(
        self,
        config: DatabaseConfig,
        target: TargetConfig,
        schema: LogicalSchema,
        script: str,
    ) -> list[ValidationResult]:
        if self.parallel_validation:
            return self._run_validators_parallel(config, target, schema, script)
        return self._run_validators_sequential(config, target, schema, script)

    def _run_validators_sequential(
        self,
        config: DatabaseConfig,
        target: TargetConfig,
        schema: LogicalSchema,
        script: str,
    ) -> list[ValidationResult]:
        results: list[ValidationResult] = []
        validators = self._get_validators_for_target(target.db_type)

        for validator in validators:
            logger.info("  [%s] Checking...", validator.name)
            start = time.time()
            result = validator.validate(config, script)
            elapsed = time.time() - start
            icon = "PASS" if result.passed else "FAIL"
            logger.info("  [%s] [%s] (%.1fs)", validator.name, icon, elapsed)
            results.append(result)

        logger.info("  [%s] Checking...", self.compliance_checker.name)
        start = time.time()
        result = self.compliance_checker.validate(target, schema, script)
        elapsed = time.time() - start
        icon = "PASS" if result.passed else "FAIL"
        logger.info("  [%s] [%s] (%.1fs)", self.compliance_checker.name, icon, elapsed)
        results.append(result)

        return results

    def _run_validators_parallel(
        self,
        config: DatabaseConfig,
        target: TargetConfig,
        schema: LogicalSchema,
        script: str,
    ) -> list[ValidationResult]:
        results: list[ValidationResult] = []
        validators = self._get_validators_for_target(target.db_type)

        with concurrent.futures.ThreadPoolExecutor(
            max_workers=len(validators) + 1
        ) as executor:
            future_to_name: dict[concurrent.futures.Future, str] = {}

            for validator in validators:
                future = executor.submit(validator.validate, config, script)
                future_to_name[future] = validator.name

            compliance_future = executor.submit(
                self.compliance_checker.validate, target, schema, script
            )
            future_to_name[compliance_future] = self.compliance_checker.name

            for future in concurrent.futures.as_completed(future_to_name):
                name = future_to_name[future]
                try:
                    result = future.result()
                    icon = "PASS" if result.passed else "FAIL"
                    logger.info("  [%s] [%s]", name, icon)
                    results.append(result)
                except Exception as e:
                    logger.error("  [%s] [ERROR] %s", name, e)
                    results.append(
                        ValidationResult(
                            agent_name=name,
                            status=ValidationStatus.FAIL,
                            feedback=f"Validator error: {e}",
                            details=str(e),
                        )
                    )

        return results
