from __future__ import annotations

import json
import logging
import sys

from dotenv import load_dotenv
from fastapi import FastAPI

from dbagnets.log_context import LogContextFilter
from dbagnets.models.api import (
    GenerateRequest,
    GenerateResponse,
    ScriptResult,
    resolve_container,
)
from dbagnets.models.config import PipelineConfig, TargetConfig
from dbagnets.orchestrators import PipelineOrchestrator

load_dotenv()

app = FastAPI(
    title="DBagnets",
    description="Generate database initialization scripts for any database engine via LLM agents.",
    version="0.1.0",
)


def _setup_logging() -> None:
    formatter = logging.Formatter(
        fmt="%(asctime)s | %(levelname)-5s | %(ctx)-12s | %(message)s",
        datefmt="%H:%M:%S",
    )
    handler = logging.StreamHandler(sys.stderr)
    handler.setFormatter(formatter)

    logger = logging.getLogger("dbagnets")
    if not logger.handlers:
        logger.setLevel(logging.INFO)
        logger.addFilter(LogContextFilter())
        logger.addHandler(handler)


_setup_logging()


@app.post("/generate", response_model=GenerateResponse)
def generate(request: GenerateRequest) -> GenerateResponse:
    targets = [
        TargetConfig(
            db_type=t.db_type,
            db_name=t.db_name,
            db_version=t.db_version,
        )
        for t in request.targets
    ]

    pipeline_config = PipelineConfig(
        idea=request.idea,
        depth=request.depth,
        targets=targets,
    )

    orchestrator = PipelineOrchestrator(
        model=request.model,
        max_iterations=request.max_iterations,
        parallel_validation=not request.sequential,
    )

    result = orchestrator.run(pipeline_config)

    logical_schema = None
    if result.schema_result.final_schema_json:
        logical_schema = json.loads(result.schema_result.final_schema_json)

    scripts: list[ScriptResult] = []
    for sr in result.script_results:
        container = resolve_container(sr.target.db_name, sr.target.db_version)
        scripts.append(
            ScriptResult(
                db_type=sr.target.db_type,
                db_name=sr.target.db_name,
                db_version=sr.target.db_version,
                container=container,
                script=sr.final_script or "",
                success=sr.success,
                iterations_used=sr.current_iteration,
            )
        )

    return GenerateResponse(
        success=result.all_succeeded,
        logical_schema=logical_schema,
        scripts=scripts,
    )
