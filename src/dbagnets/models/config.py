from __future__ import annotations

from pydantic import BaseModel, ConfigDict

from dbagnets.models.enums import DatabaseType


class DatabaseConfig(BaseModel):
    model_config = ConfigDict(frozen=True)

    db_type: DatabaseType
    db_name: str          # e.g. "postgresql", "neo4j", "milvus"
    db_version: str       # e.g. "13", "5.0", "2.3"
    idea: str             # e.g. "movie management database"
    depth: int            # relationship depth, e.g. 4
