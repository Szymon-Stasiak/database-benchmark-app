from __future__ import annotations

import pytest
from pydantic import ValidationError

from dbagnets.models.api import (
    ContainerInfo,
    GenerateRequest,
    GenerateResponse,
    ScriptResult,
    TargetRequest,
    resolve_container,
)
from dbagnets.models.enums import DatabaseType


class TestTargetRequest:
    def test_creates_from_valid_data(self):
        t = TargetRequest(
            db_type=DatabaseType.RELATIONAL,
            db_name="postgresql",
            db_version="16",
        )
        assert t.db_type == DatabaseType.RELATIONAL
        assert t.db_name == "postgresql"
        assert t.db_version == "16"

    def test_rejects_invalid_db_type(self):
        with pytest.raises(ValidationError):
            TargetRequest(db_type="nosql", db_name="x", db_version="1")


class TestGenerateRequest:
    def test_creates_with_defaults(self):
        req = GenerateRequest(
            idea="test",
            depth=3,
            targets=[
                TargetRequest(
                    db_type=DatabaseType.RELATIONAL,
                    db_name="postgresql",
                    db_version="16",
                )
            ],
        )
        assert req.model == "vertex_ai/claude-sonnet-4-6"
        assert req.max_iterations == 10
        assert req.sequential is False

    def test_rejects_empty_targets(self):
        with pytest.raises(ValidationError):
            GenerateRequest(idea="test", depth=3, targets=[])

    def test_rejects_zero_depth(self):
        with pytest.raises(ValidationError):
            GenerateRequest(
                idea="test",
                depth=0,
                targets=[
                    TargetRequest(
                        db_type=DatabaseType.GRAPH,
                        db_name="neo4j",
                        db_version="5",
                    )
                ],
            )

    def test_rejects_too_many_iterations(self):
        with pytest.raises(ValidationError):
            GenerateRequest(
                idea="test",
                depth=2,
                targets=[
                    TargetRequest(
                        db_type=DatabaseType.GRAPH,
                        db_name="neo4j",
                        db_version="5",
                    )
                ],
                max_iterations=51,
            )


class TestContainerInfo:
    def test_creates_from_values(self):
        c = ContainerInfo(
            docker_image="postgres:16",
            default_port=5432,
            environment={"POSTGRES_PASSWORD": "x"},
        )
        assert c.docker_image == "postgres:16"
        assert c.default_port == 5432
        assert c.environment == {"POSTGRES_PASSWORD": "x"}


class TestScriptResult:
    def test_creates_full_result(self):
        sr = ScriptResult(
            db_type=DatabaseType.RELATIONAL,
            db_name="postgresql",
            db_version="16",
            container=ContainerInfo(
                docker_image="postgres:16",
                default_port=5432,
                environment={},
            ),
            script="CREATE TABLE t(id INT);",
            success=True,
            iterations_used=2,
        )
        assert sr.success is True
        assert sr.iterations_used == 2


class TestGenerateResponse:
    def test_creates_successful_response(self):
        resp = GenerateResponse(
            success=True,
            logical_schema={"idea": "test"},
            scripts=[],
        )
        assert resp.success is True
        assert resp.logical_schema == {"idea": "test"}

    def test_creates_failed_response_without_schema(self):
        resp = GenerateResponse(success=False, scripts=[])
        assert resp.logical_schema is None


class TestResolveContainer:
    @pytest.mark.parametrize(
        "db_name, db_version, expected_image, expected_port",
        [
            ("postgresql", "16", "postgres:16", 5432),
            ("mysql", "8.0", "mysql:8.0", 3306),
            ("neo4j", "5.0", "neo4j:5.0", 7687),
            ("mongodb", "7.0", "mongo:7.0", 27017),
            ("redis", "7", "redis:7", 6379),
            ("milvus", "2.3", "milvusdb/milvus:v2.3", 19530),
            ("qdrant", "1.8", "qdrant/qdrant:v1.8", 6333),
            ("couchdb", "3.3", "couchdb:3.3", 5984),
            ("dynamodb", "2.0", "amazon/dynamodb-local:2.0", 8000),
            ("influxdb", "2.7", "influxdb:2.7", 8086),
            ("timescaledb", "16", "timescale/timescaledb:latest-pg16", 5432),
        ],
    )
    def test_resolves_known_databases(
        self, db_name, db_version, expected_image, expected_port
    ):
        container = resolve_container(db_name, db_version)
        assert container.docker_image == expected_image
        assert container.default_port == expected_port

    def test_fallback_for_unknown_database(self):
        container = resolve_container("cockroachdb", "23.1")
        assert container.docker_image == "cockroachdb:23.1"
        assert container.default_port == 0

    def test_environment_preserved(self):
        container = resolve_container("postgresql", "16")
        assert "POSTGRES_PASSWORD" in container.environment
        assert "POSTGRES_DB" in container.environment
