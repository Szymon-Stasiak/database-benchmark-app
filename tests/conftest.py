from __future__ import annotations

import pytest
from unittest.mock import MagicMock

from models import DatabaseConfig, DatabaseType


@pytest.fixture
def mock_client():
    return MagicMock()


@pytest.fixture
def sample_config():
    return DatabaseConfig(
        db_type=DatabaseType.RELATIONAL,
        db_name="postgresql",
        db_version="16",
        idea="movie management database",
        depth=4,
    )