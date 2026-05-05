from __future__ import annotations

from dbagnets.agents.schema.depth_checker import SchemaDepthChecker
from dbagnets.models.enums import (
    AbstractDataType,
    RelationshipCardinality,
    ValidationStatus,
)
from dbagnets.models.schema import Attribute, Entity, LogicalSchema, Relationship


def _entity(name: str) -> Entity:
    return Entity(
        name=name,
        attributes=[Attribute(name="id", data_type=AbstractDataType.INTEGER)],
    )


def _rel(name: str, src: str, tgt: str, card: RelationshipCardinality = RelationshipCardinality.ONE_TO_MANY) -> Relationship:
    return Relationship(
        name=name, source_entity=src, target_entity=tgt, cardinality=card,
    )


class TestSchemaDepthChecker:
    def test_name_returns_schema_depth_checker(self):
        checker = SchemaDepthChecker()
        assert checker.name == "SchemaDepthChecker"


class TestValidate:
    def test_passes_when_depth_matches(self):
        # Linear chain: a -> b -> c  => depth = 2
        schema = LogicalSchema(
            idea="test", depth=2,
            entities=[_entity("a"), _entity("b"), _entity("c")],
            relationships=[_rel("a_to_b", "a", "b"), _rel("b_to_c", "b", "c")],
        )
        checker = SchemaDepthChecker()
        result = checker.validate(schema)

        assert result.status == ValidationStatus.PASS
        assert result.agent_name == "SchemaDepthChecker"

    def test_fails_when_depth_too_shallow(self):
        # a -> b  => depth = 1, but required depth = 3
        schema = LogicalSchema(
            idea="test", depth=3,
            entities=[_entity("a"), _entity("b")],
            relationships=[_rel("a_to_b", "a", "b")],
        )
        checker = SchemaDepthChecker()
        result = checker.validate(schema)

        assert result.status == ValidationStatus.FAIL
        assert "1" in result.feedback
        assert "3" in result.feedback

    def test_fails_when_depth_too_deep(self):
        # a -> b -> c -> d  => depth = 3, but required depth = 2
        schema = LogicalSchema(
            idea="test", depth=2,
            entities=[_entity("a"), _entity("b"), _entity("c"), _entity("d")],
            relationships=[
                _rel("a_to_b", "a", "b"),
                _rel("b_to_c", "b", "c"),
                _rel("c_to_d", "c", "d"),
            ],
        )
        checker = SchemaDepthChecker()
        result = checker.validate(schema)

        assert result.status == ValidationStatus.FAIL
        assert "3" in result.feedback
        assert "2" in result.feedback

    def test_handles_empty_schema(self):
        schema = LogicalSchema(
            idea="test", depth=0,
            entities=[],
            relationships=[],
        )
        checker = SchemaDepthChecker()
        result = checker.validate(schema)

        assert result.status == ValidationStatus.PASS

    def test_handles_single_entity_no_relationships(self):
        schema = LogicalSchema(
            idea="test", depth=0,
            entities=[_entity("a")],
            relationships=[],
        )
        checker = SchemaDepthChecker()
        result = checker.validate(schema)

        assert result.status == ValidationStatus.PASS

    def test_handles_tree_structure(self):
        # a -> b, a -> c, b -> d  => longest path = 2 (a -> b -> d)
        schema = LogicalSchema(
            idea="test", depth=2,
            entities=[_entity("a"), _entity("b"), _entity("c"), _entity("d")],
            relationships=[
                _rel("a_to_b", "a", "b"),
                _rel("a_to_c", "a", "c"),
                _rel("b_to_d", "b", "d"),
            ],
        )
        checker = SchemaDepthChecker()
        result = checker.validate(schema)

        assert result.status == ValidationStatus.PASS

    def test_handles_cycle(self):
        # a -> b -> c -> a, depth=2 (longest non-cyclic path is 2: a->b->c)
        schema = LogicalSchema(
            idea="test", depth=2,
            entities=[_entity("a"), _entity("b"), _entity("c")],
            relationships=[
                _rel("a_to_b", "a", "b"),
                _rel("b_to_c", "b", "c"),
                _rel("c_to_a", "c", "a"),
            ],
        )
        checker = SchemaDepthChecker()
        result = checker.validate(schema)

        assert result.status == ValidationStatus.PASS

    def test_mn_relationship_counts_as_one_hop(self):
        # a --(M:N)--> b  => depth = 1
        schema = LogicalSchema(
            idea="test", depth=1,
            entities=[_entity("a"), _entity("b")],
            relationships=[
                _rel("a_to_b", "a", "b", RelationshipCardinality.MANY_TO_MANY),
            ],
        )
        checker = SchemaDepthChecker()
        result = checker.validate(schema)

        assert result.status == ValidationStatus.PASS


class TestDepthChainValidation:
    def test_passes_with_valid_depth_chain(self):
        schema = LogicalSchema(
            idea="test", depth=2,
            depth_chain=["a", "b", "c"],
            entities=[_entity("a"), _entity("b"), _entity("c")],
            relationships=[_rel("a_b", "a", "b"), _rel("b_c", "b", "c")],
        )
        checker = SchemaDepthChecker()
        result = checker.validate(schema)

        assert result.status == ValidationStatus.PASS

    def test_fails_when_chain_length_wrong(self):
        schema = LogicalSchema(
            idea="test", depth=2,
            depth_chain=["a", "b"],
            entities=[_entity("a"), _entity("b"), _entity("c")],
            relationships=[_rel("a_b", "a", "b"), _rel("b_c", "b", "c")],
        )
        checker = SchemaDepthChecker()
        result = checker.validate(schema)

        assert result.status == ValidationStatus.FAIL
        assert "2 entities" in result.feedback
        assert "expected 3" in result.feedback

    def test_fails_when_chain_references_missing_entity(self):
        schema = LogicalSchema(
            idea="test", depth=1,
            depth_chain=["a", "missing"],
            entities=[_entity("a"), _entity("b")],
            relationships=[_rel("a_b", "a", "b")],
        )
        checker = SchemaDepthChecker()
        result = checker.validate(schema)

        assert result.status == ValidationStatus.FAIL
        assert "missing" in result.feedback

    def test_fails_when_chain_relationship_missing(self):
        schema = LogicalSchema(
            idea="test", depth=2,
            depth_chain=["a", "b", "c"],
            entities=[_entity("a"), _entity("b"), _entity("c")],
            relationships=[_rel("a_b", "a", "b")],
        )
        checker = SchemaDepthChecker()
        result = checker.validate(schema)

        assert result.status == ValidationStatus.FAIL
        assert "b" in result.feedback
        assert "c" in result.feedback

    def test_skips_chain_validation_when_empty(self):
        schema = LogicalSchema(
            idea="test", depth=2,
            entities=[_entity("a"), _entity("b"), _entity("c")],
            relationships=[_rel("a_b", "a", "b"), _rel("b_c", "b", "c")],
        )
        checker = SchemaDepthChecker()
        result = checker.validate(schema)

        assert result.status == ValidationStatus.PASS
