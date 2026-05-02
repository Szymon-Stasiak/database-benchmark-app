from __future__ import annotations

from pydantic import BaseModel, Field

from dbagnets.models.enums import DatabaseType


class TargetRequest(BaseModel):
    db_type: DatabaseType
    db_name: str = Field(examples=["postgresql"])
    db_version: str = Field(examples=["16"])


class GenerateRequest(BaseModel):
    idea: str = Field(
        examples=["movie management system with actors, directors, genres and reviews"],
    )
    depth: int = Field(ge=1, examples=[4])
    targets: list[TargetRequest] = Field(min_length=1)
    model: str = "vertex_ai/claude-sonnet-4-6"
    max_iterations: int = Field(default=10, ge=1, le=50)
    sequential: bool = False


class ContainerInfo(BaseModel):
    docker_image: str
    default_port: int
    environment: dict[str, str]


class ScriptResult(BaseModel):
    db_type: DatabaseType
    db_name: str
    db_version: str
    container: ContainerInfo
    script: str
    success: bool
    iterations_used: int


class GenerateResponse(BaseModel):
    success: bool
    logical_schema: dict | None = None
    scripts: list[ScriptResult]


_CONTAINER_REGISTRY: dict[str, ContainerInfo] = {
    "postgresql": ContainerInfo(
        docker_image="postgres:{version}",
        default_port=5432,
        environment={"POSTGRES_PASSWORD": "postgres", "POSTGRES_DB": "benchmark"},
    ),
    "mysql": ContainerInfo(
        docker_image="mysql:{version}",
        default_port=3306,
        environment={"MYSQL_ROOT_PASSWORD": "root", "MYSQL_DATABASE": "benchmark"},
    ),
    "sqlite": ContainerInfo(
        docker_image="kesilent/sqlite-web:{version}",
        default_port=8080,
        environment={},
    ),
    "neo4j": ContainerInfo(
        docker_image="neo4j:{version}",
        default_port=7687,
        environment={"NEO4J_AUTH": "neo4j/benchmark"},
    ),
    "milvus": ContainerInfo(
        docker_image="milvusdb/milvus:v{version}-standalone",
        default_port=19530,
        environment={"ETCD_USE_EMBED": "true"},
    ),
    "qdrant": ContainerInfo(
        docker_image="qdrant/qdrant:v{version}",
        default_port=6333,
        environment={},
    ),
    "mongodb": ContainerInfo(
        docker_image="mongo:{version}",
        default_port=27017,
        environment={},
    ),
    "couchdb": ContainerInfo(
        docker_image="couchdb:{version}",
        default_port=5984,
        environment={"COUCHDB_USER": "admin", "COUCHDB_PASSWORD": "admin"},
    ),
    "redis": ContainerInfo(
        docker_image="redis:{version}",
        default_port=6379,
        environment={},
    ),
    "dynamodb": ContainerInfo(
        docker_image="amazon/dynamodb-local:{version}",
        default_port=8000,
        environment={},
    ),
    "timescaledb": ContainerInfo(
        docker_image="timescale/timescaledb:latest-pg{version}",
        default_port=5432,
        environment={"POSTGRES_PASSWORD": "postgres", "POSTGRES_DB": "benchmark"},
    ),
    "influxdb": ContainerInfo(
        docker_image="influxdb:{version}",
        default_port=8086,
        environment={"DOCKER_INFLUXDB_INIT_MODE": "setup", "DOCKER_INFLUXDB_INIT_USERNAME": "admin", "DOCKER_INFLUXDB_INIT_PASSWORD": "adminadmin", "DOCKER_INFLUXDB_INIT_ORG": "benchmark", "DOCKER_INFLUXDB_INIT_BUCKET": "benchmark"},
    ),
}

_FALLBACK_CONTAINER = ContainerInfo(
    docker_image="{db_name}:{version}",
    default_port=0,
    environment={},
)


def resolve_container(db_name: str, db_version: str) -> ContainerInfo:
    template = _CONTAINER_REGISTRY.get(db_name, _FALLBACK_CONTAINER)
    return ContainerInfo(
        docker_image=template.docker_image.format(
            version=db_version, db_name=db_name,
        ),
        default_port=template.default_port,
        environment=template.environment,
    )