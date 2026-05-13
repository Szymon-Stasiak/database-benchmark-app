from __future__ import annotations

from dbagnets.models.enums import AbstractDataType
from dbagnets.models.type_mapping import (
    format_type_mapping_prompt,
    get_all_type_hints,
    get_type_hint,
)


class TestGetTypeHint:
    def test_returns_correct_mapping_for_known_db_and_type(self):
        result = get_type_hint(AbstractDataType.STRING, "postgresql")
        assert result == "VARCHAR(255)"

    def test_returns_none_for_unknown_db(self):
        result = get_type_hint(AbstractDataType.STRING, "unknowndb")
        assert result is None


class TestGetAllTypeHints:
    def test_returns_dict_for_known_db(self):
        result = get_all_type_hints("postgresql")
        assert isinstance(result, dict)
        assert len(result) > 0
        assert AbstractDataType.STRING in result

    def test_returns_empty_dict_for_unknown_db(self):
        result = get_all_type_hints("unknowndb")
        assert result == {}


class TestFormatTypeMappingPrompt:
    def test_returns_nonempty_string_for_known_db(self):
        result = format_type_mapping_prompt("postgresql")
        assert len(result) > 0

    def test_returns_empty_string_for_unknown_db(self):
        result = format_type_mapping_prompt("unknowndb")
        assert result == ""

    def test_contains_data_type_mapping_header(self):
        result = format_type_mapping_prompt("postgresql")
        assert "DATA TYPE MAPPING" in result
