
from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

from dotenv import load_dotenv

from models import DatabaseConfig, DatabaseType
from orchestrator import AgentOrchestrator


DB_TYPE_MAP = {
    "relational": DatabaseType.RELATIONAL,
    "graph": DatabaseType.GRAPH,
    "vector": DatabaseType.VECTOR,
    "document": DatabaseType.DOCUMENT,
    "key_value": DatabaseType.KEY_VALUE,
    "time_series": DatabaseType.TIME_SERIES,
}


def setup_logging(verbose: bool) -> None:
    level = logging.DEBUG if verbose else logging.INFO
    formatter = logging.Formatter(
        fmt="%(asctime)s | %(levelname)-5s | %(message)s",
        datefmt="%H:%M:%S",
    )
    handler = logging.StreamHandler(sys.stderr)
    handler.setFormatter(formatter)

    logger = logging.getLogger("dbagnets")
    logger.setLevel(level)
    logger.addHandler(handler)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="DBagnets - Agent Loop for generating database initialization scripts",
    )
    parser.add_argument(
        "--db-type",
        required=True,
        choices=list(DB_TYPE_MAP.keys()),
        help="Database type",
    )
    parser.add_argument(
        "--db-name",
        required=True,
        help="Database engine name (e.g. postgresql, mysql, neo4j, milvus)",
    )
    parser.add_argument(
        "--db-version",
        required=True,
        help="Database version (e.g. 13, 8.0, 5.0)",
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
        "--output",
        type=str,
        default=None,
        help="Output file path for the script (default: stdout)",
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
    return parser.parse_args()


def main() -> int:
    load_dotenv()
    args = parse_args()
    setup_logging(args.verbose)

    logger = logging.getLogger("dbagnets")

    config = DatabaseConfig(
        db_type=DB_TYPE_MAP[args.db_type],
        db_name=args.db_name,
        db_version=args.db_version,
        idea=args.idea,
        depth=args.depth,
    )

    orchestrator = AgentOrchestrator(
        model=args.model,
        max_iterations=args.max_iterations,
        parallel_validation=not args.sequential,
    )

    state = orchestrator.run(config)

    if state.final_script:
        if args.output:
            output_path = Path(args.output)
            output_path.write_text(state.final_script, encoding="utf-8")
            logger.info("Script saved to: %s", output_path)
        else:
            logger.info("")
            logger.info("=" * 60)
            logger.info("  GENERATED SCRIPT:")
            logger.info("=" * 60)
            print(state.final_script)

    return 0 if state.success else 1


if __name__ == "__main__":
    sys.exit(main())
