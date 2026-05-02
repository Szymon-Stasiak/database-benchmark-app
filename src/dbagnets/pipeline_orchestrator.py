from __future__ import annotations

import concurrent.futures
import logging
import time

from dbagnets.models import PipelineResult, ScriptLoopState
from dbagnets.models.config import PipelineConfig, TargetConfig
from dbagnets.models.schema import LogicalSchema
from dbagnets.models.state import SchemaLoopState
from dbagnets.schema_orchestrator import SchemaOrchestrator
from dbagnets.script_orchestrator import ScriptOrchestrator

logger = logging.getLogger("dbagnets")


class PipelineOrchestrator:

    def __init__(
        self,
        model: str = "vertex_ai/claude-sonnet-4-6",
        max_iterations: int = 10,
        parallel_validation: bool = True,
    ):
        self.model = model
        self.max_iterations = max_iterations
        self.parallel_validation = parallel_validation

    def run(self, config: PipelineConfig) -> PipelineResult:
        total_start = time.time()

        logger.info("=" * 60)
        logger.info("  DBagnets - Pipeline")
        logger.info("=" * 60)
        logger.info("  Idea: %s", config.idea)
        logger.info("  Depth: %d", config.depth)
        logger.info("  Targets: %s", ", ".join(f"{t.db_name} {t.db_version}" for t in config.targets))
        logger.info("  Max iterations: %d", self.max_iterations)
        logger.info("  Model: %s", self.model)
        logger.info("=" * 60)

        schema_orch = SchemaOrchestrator(
            model=self.model,
            max_iterations=self.max_iterations,
            parallel_validation=self.parallel_validation,
        )
        schema_result = schema_orch.run(idea=config.idea, depth=config.depth)

        if not schema_result.success or schema_result.final_schema_json is None:
            logger.error("Schema generation failed. Skipping script generation.")
            total_elapsed = time.time() - total_start
            logger.info("  Total time: %.1fs", total_elapsed)
            return PipelineResult(
                schema_result=schema_result,
                script_results=[],
            )

        schema = LogicalSchema.model_validate_json(schema_result.final_schema_json)
        logger.info("")
        logger.info("=" * 60)
        logger.info("  Phase 2: Parallel Script Generation (%d targets)", len(config.targets))
        logger.info("=" * 60)

        script_results = self._run_targets_parallel(config, schema)

        total_elapsed = time.time() - total_start
        logger.info("")
        logger.info("=" * 60)
        logger.info("  Pipeline Complete")
        logger.info("=" * 60)

        succeeded = sum(1 for r in script_results if r.success)
        logger.info("  Results: %d/%d targets succeeded", succeeded, len(config.targets))
        logger.info("  Total time: %.1fs", total_elapsed)

        return PipelineResult(
            schema_result=schema_result,
            script_results=script_results,
        )

    def _run_targets_parallel(
        self, config: PipelineConfig, schema: LogicalSchema
    ) -> list[ScriptLoopState]:
        with concurrent.futures.ThreadPoolExecutor(
            max_workers=len(config.targets)
        ) as executor:
            future_to_target = {
                executor.submit(
                    self._run_single_target, target, schema, config.idea, config.depth
                ): target
                for target in config.targets
            }

            results: list[ScriptLoopState] = []
            for future in concurrent.futures.as_completed(future_to_target):
                target = future_to_target[future]
                try:
                    results.append(future.result())
                except Exception as e:
                    logger.error("[%s] Target failed with error: %s", target.db_name, e)
                    results.append(
                        ScriptLoopState(
                            target=target,
                            max_iterations=self.max_iterations,
                            success=False,
                        )
                    )

            return results

    def _run_single_target(
        self,
        target: TargetConfig,
        schema: LogicalSchema,
        idea: str,
        depth: int,
    ) -> ScriptLoopState:
        script_orch = ScriptOrchestrator(
            model=self.model,
            max_iterations=self.max_iterations,
            parallel_validation=self.parallel_validation,
        )
        return script_orch.run(target=target, schema=schema, idea=idea, depth=depth)
