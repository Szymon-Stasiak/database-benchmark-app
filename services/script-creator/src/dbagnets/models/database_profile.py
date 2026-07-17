from __future__ import annotations

from dataclasses import dataclass

from dbagnets.models.enums import DatabaseType

@dataclass(frozen=True)
class DatabaseProfile:
    db_type: DatabaseType
    syntax_checks: str
    version_examples: str
    best_practices: str
    compliance_rules: str
    naming_rules: str
    structure_rules: str


def get_profile(db_type: DatabaseType) -> DatabaseProfile:
    from dbagnets.models.profiles import PROFILES
    return PROFILES[db_type]