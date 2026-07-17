from __future__ import annotations

import pytest

from dbagnets.models.config import TargetConfig
from dbagnets.models.enums import AbstractDataType, DatabaseType
from dbagnets.models.schema import Attribute, Entity, LogicalSchema
from dbagnets.models.validation_context import ValidationContext


def _schema() -> LogicalSchema:
    return LogicalSchema(
        idea="test",
        depth=0,
        entities=[
            Entity(
                name="t",
                attributes=[Attribute(name="id", data_type=AbstractDataType.INTEGER)],
            ),
        ],
        relationships=[],
    )


class TestValidationContext:
    def test_defaults(self):
        ctx = ValidationContext(schema=_schema())
        assert ctx.target is None
        assert ctx.script is None
        assert ctx.embedding_mappings == ()
        assert ctx.idea == ""
        assert ctx.depth == 0

    def test_profile_returns_matching_profile_when_target_set(self):
        target = TargetConfig(
            db_type=DatabaseType.RELATIONAL, db_name="postgresql", db_version="16",
        )
        ctx = ValidationContext(schema=_schema(), target=target)
        assert ctx.profile.db_type == DatabaseType.RELATIONAL

    def test_profile_raises_when_no_target(self):
        ctx = ValidationContext(schema=_schema())
        with pytest.raises(ValueError, match="requires a target"):
            _ = ctx.profile
