from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path

from dotenv import load_dotenv

from dbagnets.pipeline_orchestrator import PipelineOrchestrator
from dbagnets.log_context import LogContextFilter
from dbagnets.models import PipelineConfig, DatabaseType, TargetConfig


DB_TYPE_MAP = {
    "relational": DatabaseType.RELATIONAL,
    "graph": DatabaseType.GRAPH,
    "vector": DatabaseType.VECTOR,
    "document": DatabaseType.DOCUMENT,
    "key_value": DatabaseType.KEY_VALUE,
    "time_series": DatabaseType.TIME_SERIES,
}

_EXTENSIONS: dict[DatabaseType, str] = {
    DatabaseType.RELATIONAL: "sql",
    DatabaseType.GRAPH: "cypher",
    DatabaseType.VECTOR: "py",
    DatabaseType.DOCUMENT: "js",
    DatabaseType.KEY_VALUE: "redis",
    DatabaseType.TIME_SERIES: "sql",
}


def setup_logging(verbose: bool) -> None:
    level = logging.DEBUG if verbose else logging.INFO
    formatter = logging.Formatter(
        fmt="%(asctime)s | %(levelname)-5s | %(ctx)-12s | %(message)s",
        datefmt="%H:%M:%S",
    )
    handler = logging.StreamHandler(sys.stderr)
    handler.setFormatter(formatter)

    logger = logging.getLogger("dbagnets")
    logger.setLevel(level)
    logger.addFilter(LogContextFilter())
    logger.addHandler(handler)


def parse_target(target_str: str) -> TargetConfig:
    parts = target_str.split(":")
    if len(parts) != 3:
        raise argparse.ArgumentTypeError(
            f"Invalid target format: '{target_str}'. Expected TYPE:NAME:VERSION "
            "(e.g. relational:postgresql:16)"
        )
    db_type_str, db_name, db_version = parts
    if db_type_str not in DB_TYPE_MAP:
        raise argparse.ArgumentTypeError(
            f"Unknown database type: '{db_type_str}'. "
            f"Valid types: {', '.join(DB_TYPE_MAP.keys())}"
        )
    return TargetConfig(
        db_type=DB_TYPE_MAP[db_type_str],
        db_name=db_name,
        db_version=db_version,
    )


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="DBagnets - Multi-target database script generation pipeline",
    )
    parser.add_argument(
        "--target",
        action="append",
        required=True,
        help="Target database in TYPE:NAME:VERSION format (e.g. relational:postgresql:16). Repeatable.",
    )
    parser.add_argument(
        "--idea",
        required=True,
        help='Database description/idea (e.g. "movie management system")',
    )
    parser.add_argument(
        "--depth",
        required=True,
        type=int,
        help="Relationship depth (e.g. 4)",
    )
    parser.add_argument(
        "--max-iterations",
        type=int,
        default=10,
        help="Maximum loop iterations (default: 10)",
    )
    parser.add_argument(
        "--model",
        default="vertex_ai/claude-sonnet-4-6",
        help="LiteLLM model string (default: vertex_ai/claude-sonnet-4-6)",
    )
    parser.add_argument(
        "--output-dir",
        type=str,
        default=None,
        help="Output directory (saves schema.json + per-target scripts)",
    )
    parser.add_argument(
        "--sequential",
        action="store_true",
        help="Run validators sequentially (default: parallel)",
    )
    parser.add_argument(
        "-v", "--verbose",
        action="store_true",
        help="Enable debug logging (shows prompts, token counts, etc.)",
    )
    return parser.parse_args(argv)


def _run_pipeline(args: argparse.Namespace) -> int:
    logger = logging.getLogger("dbagnets")

    targets = [parse_target(t) for t in args.target]
    benchmark_config = PipelineConfig(
        idea=args.idea,
        depth=args.depth,
        targets=targets,
    )

    orchestrator = PipelineOrchestrator(
        model=args.model,
        max_iterations=args.max_iterations,
        parallel_validation=not args.sequential,
    )

    result = orchestrator.run(benchmark_config)

    if args.output_dir:
        output_dir = Path(args.output_dir)
        output_dir.mkdir(parents=True, exist_ok=True)

        if result.schema_result.final_schema_json:
            schema_path = output_dir / "schema.json"
            schema_data = json.loads(result.schema_result.final_schema_json)
            schema_path.write_text(
                json.dumps(schema_data, indent=2, ensure_ascii=False),
                encoding="utf-8",
            )
            logger.info("Schema saved to: %s", schema_path)

        for script_result in result.script_results:
            if script_result.final_script:
                ext = _EXTENSIONS.get(script_result.target.db_type, "txt")
                filename = f"{script_result.target.db_name}_{script_result.target.db_version}.{ext}"
                script_path = output_dir / filename
                script_path.write_text(script_result.final_script, encoding="utf-8")
                logger.info("Script saved to: %s", script_path)
    else:
        if result.schema_result.final_schema_json:
            logger.info("")
            logger.info("=" * 60)
            logger.info("  LOGICAL SCHEMA:")
            logger.info("=" * 60)
            print(result.schema_result.final_schema_json)

        for script_result in result.script_results:
            if script_result.final_script:
                logger.info("")
                logger.info("=" * 60)
                logger.info(
                    "  SCRIPT: %s %s",
                    script_result.target.db_name,
                    script_result.target.db_version,
                )
                logger.info("=" * 60)
                print(script_result.final_script)

    return 0 if result.all_succeeded else 1


def main() -> int:
    load_dotenv()
    args = parse_args()
    setup_logging(args.verbose)
    return _run_pipeline(args)


if __name__ == "__main__":
    sys.exit(main())
