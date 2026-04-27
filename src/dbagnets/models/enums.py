from __future__ import annotations

from enum import Enum


class DatabaseType(Enum):
    RELATIONAL = "relational"
    GRAPH = "graph"
    VECTOR = "vector"
    DOCUMENT = "document"
    KEY_VALUE = "key_value"
    TIME_SERIES = "time_series"


class ValidationStatus(Enum):
    PASS = "PASS"
    FAIL = "FAIL"
