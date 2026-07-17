from __future__ import annotations

from dbagnets.models.database_profile import DatabaseProfile
from dbagnets.models.enums import DatabaseType
from dbagnets.models.profiles.document import DOCUMENT_PROFILE
from dbagnets.models.profiles.graph import GRAPH_PROFILE
from dbagnets.models.profiles.key_value import KEY_VALUE_PROFILE
from dbagnets.models.profiles.relational import RELATIONAL_PROFILE
from dbagnets.models.profiles.time_series import TIME_SERIES_PROFILE
from dbagnets.models.profiles.vector import VECTOR_PROFILE

PROFILES: dict[DatabaseType, DatabaseProfile] = {
    DatabaseType.RELATIONAL: RELATIONAL_PROFILE,
    DatabaseType.GRAPH: GRAPH_PROFILE,
    DatabaseType.VECTOR: VECTOR_PROFILE,
    DatabaseType.DOCUMENT: DOCUMENT_PROFILE,
    DatabaseType.KEY_VALUE: KEY_VALUE_PROFILE,
    DatabaseType.TIME_SERIES: TIME_SERIES_PROFILE,
}

assert set(PROFILES) == set(DatabaseType), (
    f"PROFILES is missing entries for: {set(DatabaseType) - set(PROFILES)}"
)

__all__ = ["PROFILES"]
