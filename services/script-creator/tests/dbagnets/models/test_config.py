from __future__ import annotations

import pytest
from pydantic import ValidationError

from dbagnets.models import PipelineConfig, DatabaseConfig, DatabaseType, TargetConfig


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


class TestTargetConfig:
    def test_stores_all_fields_from_constructor(self):
        tc = TargetConfig(
            db_type=DatabaseType.RELATIONAL,
            db_name="postgresql",
            db_version="16",
        )
        assert tc.db_type == DatabaseType.RELATIONAL
        assert tc.db_name == "postgresql"
        assert tc.db_version == "16"

    def test_is_frozen(self):
        tc = TargetConfig(
            db_type=DatabaseType.GRAPH,
            db_name="neo4j",
            db_version="5.0",
        )
        with pytest.raises(ValidationError):
            tc.db_name = "mysql"


class TestPipelineConfig:
    def test_stores_all_fields_from_constructor(self):
        target = TargetConfig(
            db_type=DatabaseType.RELATIONAL,
            db_name="postgresql",
            db_version="16",
        )
        bc = PipelineConfig(
            idea="movie management database",
            depth=4,
            targets=[target],
        )
        assert bc.idea == "movie management database"
        assert bc.depth == 4
        assert len(bc.targets) == 1
        assert bc.targets[0] == target

    def test_is_frozen(self):
        bc = PipelineConfig(
            idea="test",
            depth=2,
            targets=[],
        )
        with pytest.raises(ValidationError):
            bc.idea = "changed"
