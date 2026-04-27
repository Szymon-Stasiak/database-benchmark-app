from __future__ import annotations

from dbagnets.models import DatabaseType, ValidationStatus


class TestDatabaseType:
    def test_contains_all_six_supported_types(self):
        assert DatabaseType.RELATIONAL.value == "relational"
        assert DatabaseType.GRAPH.value == "graph"
        assert DatabaseType.VECTOR.value == "vector"
        assert DatabaseType.DOCUMENT.value == "document"
        assert DatabaseType.KEY_VALUE.value == "key_value"
        assert DatabaseType.TIME_SERIES.value == "time_series"


class TestValidationStatus:
    def test_contains_pass_and_fail(self):
        assert ValidationStatus.PASS.value == "PASS"
        assert ValidationStatus.FAIL.value == "FAIL"
