from __future__ import annotations

from unittest.mock import patch

from fastapi.testclient import TestClient

from dbagnets.api import app
from dbagnets.models.config import TargetConfig
from dbagnets.models.enums import DatabaseType
from dbagnets.models.state import (
    PipelineResult,
    SchemaLoopState,
    ScriptLoopState,
)

client = TestClient(app)

_PG_TARGET = TargetConfig(
    db_type=DatabaseType.RELATIONAL, db_name="postgresql", db_version="16"
)


def _make_pipeline_result(
    schema_success: bool = True,
    script_success: bool = True,
) -> PipelineResult:
    schema = SchemaLoopState(
        idea="test",
        depth=2,
        max_iterations=10,
        current_iteration=2,
        success=schema_success,
        final_schema_json='{"idea":"test","depth":2,"entities":[],"relationships":[]}'
        if schema_success
        else None,
    )
    scripts = []
    if schema_success:
        scripts.append(
            ScriptLoopState(
                target=_PG_TARGET,
                max_iterations=10,
                current_iteration=3,
                success=script_success,
                final_script="CREATE TABLE test (id INT);"
                if script_success
                else None,
            )
        )
    return PipelineResult(schema_result=schema, script_results=scripts)


_VALID_BODY = {
    "idea": "test db",
    "depth": 2,
    "targets": [
        {"db_type": "relational", "db_name": "postgresql", "db_version": "16"}
    ],
}


class TestGenerateEndpoint:
    @patch("dbagnets.api.PipelineOrchestrator")
    def test_successful_generation(self, mock_orch_cls):
        mock_orch_cls.return_value.run.return_value = _make_pipeline_result()

        resp = client.post("/generate", json=_VALID_BODY)

        assert resp.status_code == 200
        data = resp.json()
        assert data["success"] is True
        assert data["logical_schema"] is not None
        assert len(data["scripts"]) == 1

        script = data["scripts"][0]
        assert script["db_name"] == "postgresql"
        assert script["db_version"] == "16"
        assert script["container"]["docker_image"] == "postgres:16"
        assert script["container"]["default_port"] == 5432
        assert script["script"] == "CREATE TABLE test (id INT);"
        assert script["success"] is True
        assert script["iterations_used"] == 3

    @patch("dbagnets.api.PipelineOrchestrator")
    def test_failed_schema(self, mock_orch_cls):
        mock_orch_cls.return_value.run.return_value = _make_pipeline_result(
            schema_success=False,
        )

        resp = client.post("/generate", json=_VALID_BODY)

        assert resp.status_code == 200
        data = resp.json()
        assert data["success"] is False
        assert data["scripts"] == []

    @patch("dbagnets.api.PipelineOrchestrator")
    def test_failed_script(self, mock_orch_cls):
        mock_orch_cls.return_value.run.return_value = _make_pipeline_result(
            script_success=False,
        )

        resp = client.post("/generate", json=_VALID_BODY)

        assert resp.status_code == 200
        data = resp.json()
        assert data["success"] is False
        assert len(data["scripts"]) == 1
        assert data["scripts"][0]["success"] is False
        assert data["scripts"][0]["script"] == ""

    @patch("dbagnets.api.PipelineOrchestrator")
    def test_passes_model_and_iterations(self, mock_orch_cls):
        mock_orch_cls.return_value.run.return_value = _make_pipeline_result()

        body = {
            **_VALID_BODY,
            "model": "openai/gpt-4o",
            "max_iterations": 5,
            "sequential": True,
        }
        client.post("/generate", json=body)

        mock_orch_cls.assert_called_once_with(
            model="openai/gpt-4o",
            max_iterations=5,
            parallel_validation=False,
        )

    def test_rejects_missing_idea(self):
        body = {"depth": 2, "targets": _VALID_BODY["targets"]}
        resp = client.post("/generate", json=body)
        assert resp.status_code == 422

    def test_rejects_empty_targets(self):
        body = {"idea": "test", "depth": 2, "targets": []}
        resp = client.post("/generate", json=body)
        assert resp.status_code == 422

    def test_rejects_invalid_db_type(self):
        body = {
            "idea": "test",
            "depth": 2,
            "targets": [
                {"db_type": "nosql", "db_name": "x", "db_version": "1"}
            ],
        }
        resp = client.post("/generate", json=body)
        assert resp.status_code == 422
