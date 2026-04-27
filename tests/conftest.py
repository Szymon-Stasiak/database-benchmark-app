from __future__ import annotations

import logging

import pytest

from models import DatabaseConfig, DatabaseType


@pytest.fixture(autouse=True)
def _clean_logger():
    yield
    logger = logging.getLogger("dbagnets")
    logger.handlers.clear()


@pytest.fixture
def sample_config():
    return DatabaseConfig(
        db_type=DatabaseType.RELATIONAL,
        db_name="postgresql",
        db_version="16",
        idea="movie management database",
        depth=4,
    )
