from __future__ import annotations

import logging
import time

from langgraph.graph import END, StateGraph

from dbagnets.agents.schema.completeness_checker import SchemaCompletenessCheckerAgent
from dbagnets.agents.schema.depth_checker import SchemaDepthChecker
from dbagnets.agents.schema.generator import SchemaGeneratorAgent
from dbagnets.agents.schema.relationship_checker import SchemaRelationshipCheckerAgent
from dbagnets.agents.schema.topic_checker import SchemaTopicCheckerAgent
from dbagnets.log_context import set_log_context
from dbagnets.models import (
    IterationResult,
    SchemaGraphState,
    SchemaLoopState,
)
from dbagnets.models.schema import LogicalSchema
from dbagnets.models.validation_context import ValidationContext, Validator
from dbagnets.orchestrators.validator_runner import run_validators

logger = logging.getLogger("dbagnets")


class SchemaOrchestrator:

    def __init__(
        self,
        model: str = "vertex_ai/claude-sonnet-4-6",
        max_iterations: int = 10,
        parallel_validation: bool = True,
    ):
        self.model = model
        self.max_iterations = max_iterations
        self.parallel_validation = parallel_validation

        self.generator = SchemaGeneratorAgent(model)
        self.validators: list[Validator] = [
            SchemaDepthChecker(),
            SchemaTopicCheckerAgent(model),
            SchemaCompletenessCheckerAgent(model),
            SchemaRelationshipCheckerAgent(model),
        ]

        self._graph = self._build_graph()

    def _build_graph(self) -> StateGraph:
        graph = StateGraph(SchemaGraphState)
        graph.add_node("generate_schema", self._generate_node)
        graph.add_node("validate_schema", self._validate_node)

        graph.set_entry_point("generate_schema")
        graph.add_edge("generate_schema", "validate_schema")
        graph.add_conditional_edges("validate_schema", self._should_continue)

        return graph.compile()

    def _generate_node(self, state: SchemaGraphState) -> dict:
        iteration = state["current_iteration"] + 1

        logger.info("")
        logger.info("-" * 60)
        logger.info("  SCHEMA ITERATION %d/%d", iteration, state["max_iterations"])
        logger.info("-" * 60)

        feedback = state["feedback"] if state["feedback"] else None
        previous_schema_json = state["schema_json"]

        logger.info("")
        logger.info("[SchemaGenerator] Generating schema...")
        gen_start = time.time()
        schema = self.generator.generate(
            state["idea"], state["depth"], feedback, previous_schema_json
        )
        gen_elapsed = time.time() - gen_start
        logger.info(
            "[SchemaGenerator] Schema generated in %.1fs (%d entities, %d relationships)",
            gen_elapsed, len(schema.entities), len(schema.relationships),
        )

        return {
            "schema_json": schema.model_dump_json(),
            "current_iteration": iteration,
        }

    def _validate_node(self, state: SchemaGraphState) -> dict:
        schema_json = state["schema_json"]
        schema = LogicalSchema.model_validate_json(schema_json)
        iteration = state["current_iteration"]
        ctx = ValidationContext(schema=schema)

        logger.info("")
        logger.info(
            "[SchemaValidation] Running validators (%s)...",
            "parallel" if self.parallel_validation else "sequential",
        )
        val_start = time.time()
        validations = run_validators(self.validators, ctx, self.parallel_validation)
        val_elapsed = time.time() - val_start
        logger.info("[SchemaValidation] All validators finished in %.1fs", val_elapsed)

        iteration_result = IterationResult(
            iteration=iteration,
            script=schema_json,
            validations=validations,
        )

        passed = sum(1 for v in validations if v.passed)
        total = len(validations)

        logger.info("")
        logger.info(iteration_result.summary())
        logger.info("")
        logger.info("[Iteration %d] Score: %d/%d passed", iteration, passed, total)

        if iteration_result.all_passed:
            return {
                "history": [iteration_result],
                "feedback": [],
                "final_schema_json": schema_json,
                "success": True,
            }

        failed = iteration_result.failed_validations
        failed_names = [v.agent_name for v in failed]
        logger.info(
            "[Loop] %d/%d validations failed: %s",
            len(failed), total, ", ".join(failed_names),
        )

        if iteration < state["max_iterations"]:
            logger.info("[Loop] Feeding back errors to SchemaGenerator for next iteration...")

        return {
            "history": [iteration_result],
            "feedback": failed,
        }

    def _should_continue(self, state: SchemaGraphState) -> str:
        if state["success"]:
            logger.info("")
            logger.info("=" * 60)
            logger.info("  SCHEMA SUCCESS! All validations passed.")
            logger.info("  Completed in %d iteration(s)", state["current_iteration"])
            logger.info("=" * 60)
            return END

        if state["current_iteration"] >= state["max_iterations"]:
            logger.info("")
            logger.info("=" * 60)
            logger.info("  SCHEMA FAILED: Exhausted %d iterations", state["max_iterations"])
            logger.info("=" * 60)
            return END

        return "generate_schema"

    def run(self, idea: str, depth: int) -> SchemaLoopState:
        total_start = time.time()
        set_log_context("schema")

        logger.info("=" * 60)
        logger.info("  Phase 1: Schema Generation")
        logger.info("=" * 60)
        logger.info("  Idea: %s", idea)
        logger.info("  Relationship depth: %d", depth)
        logger.info("  Max iterations: %d", self.max_iterations)
        logger.info("  Model: %s", self.model)
        logger.info("=" * 60)

        if self.max_iterations == 0:
            return SchemaLoopState(idea=idea, depth=depth, max_iterations=0)

        initial_state: SchemaGraphState = {
            "idea": idea,
            "depth": depth,
            "max_iterations": self.max_iterations,
            "current_iteration": 0,
            "schema_json": None,
            "feedback": [],
            "history": [],
            "final_schema_json": None,
            "success": False,
        }

        final_state = self._graph.invoke(initial_state)

        total_elapsed = time.time() - total_start
        logger.info("  Phase 1 total time: %.1fs", total_elapsed)

        return SchemaLoopState(
            idea=idea,
            depth=depth,
            max_iterations=self.max_iterations,
            current_iteration=final_state["current_iteration"],
            history=final_state["history"],
            final_schema_json=final_state["final_schema_json"],
            success=final_state["success"],
        )
