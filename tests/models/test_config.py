from __future__ import annotations

import pytest
from pydantic import ValidationError

from models import DatabaseConfig, DatabaseType


class TestDatabaseConfig:
    def test_stores_all_fields_from_constructor(self, sample_config):
        assert sample_config.db_type == DatabaseType.RELATIONAL
        assert sample_config.db_name == "postgresql"
        assert sample_config.db_version == "16"
        assert sample_config.idea == "movie management database"
        assert sample_config.depth == 4

    def test_is_frozen(self, sample_config):
        with pytest.raises(ValidationError):
            sample_config.db_name = "mysql"

    def test_rejects_invalid_db_type(self):
        with pytest.raises(ValidationError):
            DatabaseConfig(
                db_type="invalid", db_name="pg", db_version="16", idea="x", depth=4
            )
