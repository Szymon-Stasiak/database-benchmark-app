from __future__ import annotations

import pytest

from dbagnets.agents.script.field_coverage_checker import FieldCoverageChecker
from dbagnets.models.config import TargetConfig
from dbagnets.models.enums import (
    AbstractDataType,
    DatabaseType,
    RelationshipCardinality,
    ValidationStatus,
)
from dbagnets.models.schema import (
    Attribute,
    AttributeConstraint,
    DocumentEmbeddingMapping,
    Entity,
    LogicalSchema,
    Relationship,
)
from dbagnets.models.validation_context import ValidationContext


def _ctx(target, schema, script, mappings=None):
    return ValidationContext(
        schema=schema,
        target=target,
        script=script,
        embedding_mappings=tuple(mappings or ()),
    )


def _attr(
    name: str,
    data_type: AbstractDataType = AbstractDataType.STRING,
    *,
    pk: bool = False,
    unique: bool = False,
    indexed: bool = False,
) -> Attribute:
    return Attribute(
        name=name,
        data_type=data_type,
        constraints=AttributeConstraint(
            is_primary_key=pk,
            is_unique=unique,
            is_indexed=indexed,
        ),
    )


def _entity(name: str, attrs: list[Attribute]) -> Entity:
    return Entity(name=name, attributes=attrs)


def _schema(
    entities: list[Entity],
    relationships: list[Relationship] | None = None,
) -> LogicalSchema:
    return LogicalSchema(
        idea="test",
        depth=0,
        entities=entities,
        relationships=relationships or [],
    )


def _target(db_type: DatabaseType, db_name: str = "postgresql") -> TargetConfig:
    return TargetConfig(db_type=db_type, db_name=db_name, db_version="16")


class TestName:
    def test_returns_field_coverage_checker(self):
        assert FieldCoverageChecker().name == "FieldCoverageChecker"


class TestRelational:
    def test_passes_when_all_fields_present(self):
        schema = _schema([
            _entity("users", [
                _attr("id", AbstractDataType.INTEGER, pk=True),
                _attr("name"),
                _attr("email"),
            ]),
        ])
        script = """
        CREATE TABLE users (
            id SERIAL PRIMARY KEY,
            name VARCHAR(255) NOT NULL,
            email VARCHAR(255) UNIQUE
        );
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.RELATIONAL), schema, script))

        assert result.status == ValidationStatus.PASS
        assert result.agent_name == "FieldCoverageChecker"

    def test_fails_when_attribute_missing(self):
        schema = _schema([
            _entity("users", [
                _attr("id", AbstractDataType.INTEGER, pk=True),
                _attr("name"),
                _attr("email"),
            ]),
        ])
        script = """
        CREATE TABLE users (
            id SERIAL PRIMARY KEY,
            name VARCHAR(255) NOT NULL
        );
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.RELATIONAL), schema, script))

        assert result.status == ValidationStatus.FAIL
        assert any("email" in t for t in result.todos)

    def test_fails_when_entity_missing(self):
        schema = _schema([
            _entity("users", [_attr("id", pk=True)]),
            _entity("orders", [_attr("id", pk=True)]),
        ])
        script = """
        CREATE TABLE users (
            id SERIAL PRIMARY KEY
        );
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.RELATIONAL), schema, script))

        assert result.status == ValidationStatus.FAIL
        assert any("orders" in t for t in result.todos)

    def test_passes_with_multiple_tables(self):
        schema = _schema([
            _entity("users", [_attr("id", pk=True), _attr("name")]),
            _entity("orders", [_attr("id", pk=True), _attr("total")]),
        ])
        script = """
        CREATE TABLE users (
            id SERIAL PRIMARY KEY,
            name VARCHAR(255)
        );
        CREATE TABLE orders (
            id SERIAL PRIMARY KEY,
            total DECIMAL(10, 2)
        );
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.RELATIONAL), schema, script))

        assert result.status == ValidationStatus.PASS

    def test_passes_with_alter_table(self):
        schema = _schema([
            _entity("users", [
                _attr("id", pk=True),
                _attr("name"),
                _attr("status"),
            ]),
        ])
        script = """
        CREATE TABLE users (
            id SERIAL PRIMARY KEY,
            name VARCHAR(255)
        );
        ALTER TABLE users ADD COLUMN status VARCHAR(50);
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.RELATIONAL), schema, script))

        assert result.status == ValidationStatus.PASS

    def test_passes_with_quoted_identifiers(self):
        schema = _schema([
            _entity("users", [_attr("id", pk=True), _attr("order")]),
        ])
        script = """
        CREATE TABLE "users" (
            "id" SERIAL PRIMARY KEY,
            "order" INTEGER
        );
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.RELATIONAL), schema, script))

        assert result.status == ValidationStatus.PASS

    def test_does_not_match_partial_word(self):
        schema = _schema([
            _entity("users", [_attr("id", pk=True), _attr("name")]),
        ])
        script = """
        CREATE TABLE users (
            id SERIAL PRIMARY KEY,
            username VARCHAR(255)
        );
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.RELATIONAL), schema, script))

        assert result.status == ValidationStatus.FAIL
        assert any("name" in t for t in result.todos)


class TestDocument:
    def test_passes_when_all_fields_in_collection(self):
        schema = _schema([
            _entity("users", [_attr("name"), _attr("email")]),
        ])
        script = """
        db.createCollection("users", {
            validator: {
                $jsonSchema: {
                    bsonType: "object",
                    properties: {
                        name: { bsonType: "string" },
                        email: { bsonType: "string" }
                    }
                }
            }
        });
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.DOCUMENT, "mongodb"), schema, script))

        assert result.status == ValidationStatus.PASS

    def test_fails_when_field_missing_from_collection(self):
        schema = _schema([
            _entity("users", [_attr("name"), _attr("email"), _attr("age")]),
        ])
        script = """
        db.createCollection("users", {
            validator: {
                $jsonSchema: {
                    bsonType: "object",
                    properties: {
                        name: { bsonType: "string" },
                        email: { bsonType: "string" }
                    }
                }
            }
        });
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.DOCUMENT, "mongodb"), schema, script))

        assert result.status == ValidationStatus.FAIL
        assert any("age" in t for t in result.todos)

    def test_passes_embedded_entity_in_parent_block(self):
        schema = _schema([
            _entity("users", [_attr("name"), _attr("email")]),
            _entity("addresses", [_attr("street"), _attr("city")]),
        ])
        script = """
        db.createCollection("users", {
            validator: {
                $jsonSchema: {
                    bsonType: "object",
                    properties: {
                        name: { bsonType: "string" },
                        email: { bsonType: "string" },
                        addresses: {
                            bsonType: "array",
                            items: {
                                bsonType: "object",
                                properties: {
                                    street: { bsonType: "string" },
                                    city: { bsonType: "string" }
                                }
                            }
                        }
                    }
                }
            }
        });
        """
        mappings = [
            DocumentEmbeddingMapping(
                entity_name="users", is_embedded=False,
            ),
            DocumentEmbeddingMapping(
                entity_name="addresses",
                is_embedded=True,
                parent_entity="users",
                field_name="addresses",
            ),
        ]
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.DOCUMENT, "mongodb"), schema, script, mappings))

        assert result.status == ValidationStatus.PASS

    def test_fails_embedded_entity_missing_field(self):
        schema = _schema([
            _entity("users", [_attr("name")]),
            _entity("addresses", [_attr("street"), _attr("city"), _attr("zip_code")]),
        ])
        script = """
        db.createCollection("users", {
            validator: {
                $jsonSchema: {
                    bsonType: "object",
                    properties: {
                        name: { bsonType: "string" },
                        addresses: {
                            bsonType: "array",
                            items: {
                                bsonType: "object",
                                properties: {
                                    street: { bsonType: "string" },
                                    city: { bsonType: "string" }
                                }
                            }
                        }
                    }
                }
            }
        });
        """
        mappings = [
            DocumentEmbeddingMapping(
                entity_name="users", is_embedded=False,
            ),
            DocumentEmbeddingMapping(
                entity_name="addresses",
                is_embedded=True,
                parent_entity="users",
                field_name="addresses",
            ),
        ]
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.DOCUMENT, "mongodb"), schema, script, mappings))

        assert result.status == ValidationStatus.FAIL
        assert any("zip_code" in t for t in result.todos)


class TestGraph:
    def test_passes_constrained_attributes_present(self):
        schema = _schema([
            _entity("movie", [
                _attr("movie_id", pk=True),
                _attr("title", indexed=True),
                _attr("description"),
            ]),
        ])
        script = """
        CREATE CONSTRAINT movie_id_unique FOR (n:Movie) REQUIRE n.movie_id IS UNIQUE;
        CREATE INDEX movie_title_idx FOR (n:Movie) ON (n.title);
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.GRAPH, "neo4j"), schema, script))

        assert result.status == ValidationStatus.PASS

    def test_skips_unconstrained_attributes(self):
        schema = _schema([
            _entity("movie", [
                _attr("movie_id", pk=True),
                _attr("title"),
                _attr("description"),
            ]),
        ])
        script = """
        CREATE CONSTRAINT movie_id_unique FOR (n:Movie) REQUIRE n.movie_id IS UNIQUE;
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.GRAPH, "neo4j"), schema, script))

        assert result.status == ValidationStatus.PASS
        assert "movie.title" in result.details
        assert "movie.description" in result.details

    def test_fails_when_constrained_attribute_missing(self):
        schema = _schema([
            _entity("movie", [
                _attr("movie_id", pk=True),
                _attr("title", unique=True),
            ]),
        ])
        script = """
        CREATE CONSTRAINT movie_id_unique FOR (n:Movie) REQUIRE n.movie_id IS UNIQUE;
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.GRAPH, "neo4j"), schema, script))

        assert result.status == ValidationStatus.FAIL
        assert any("title" in t for t in result.todos)

    def test_handles_pascal_case_labels(self):
        schema = _schema([
            _entity("movie_review", [
                _attr("review_id", pk=True),
            ]),
        ])
        script = """
        CREATE CONSTRAINT review_id_unique FOR (n:MovieReview) REQUIRE n.review_id IS UNIQUE;
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.GRAPH, "neo4j"), schema, script))

        assert result.status == ValidationStatus.PASS

    def test_skips_fk_attributes_encoded_as_relationships(self):
        schema = _schema(
            entities=[
                _entity("director", [_attr("director_id", pk=True), _attr("name")]),
                _entity("movie", [
                    _attr("movie_id", pk=True),
                    _attr("title", indexed=True),
                    _attr("director_id", indexed=True),
                ]),
                _entity("user", [_attr("user_id", pk=True)]),
                _entity("review", [
                    _attr("review_id", pk=True),
                    _attr("movie_id", indexed=True),
                    _attr("user_id", indexed=True),
                    _attr("rating"),
                ]),
            ],
            relationships=[
                Relationship(
                    name="director_directs",
                    source_entity="director",
                    target_entity="movie",
                    cardinality=RelationshipCardinality.ONE_TO_MANY,
                ),
                Relationship(
                    name="movie_has_reviews",
                    source_entity="movie",
                    target_entity="review",
                    cardinality=RelationshipCardinality.ONE_TO_MANY,
                ),
                Relationship(
                    name="user_writes_reviews",
                    source_entity="user",
                    target_entity="review",
                    cardinality=RelationshipCardinality.ONE_TO_MANY,
                ),
            ],
        )
        script = """
        CREATE CONSTRAINT director_id_unique FOR (n:Director) REQUIRE n.director_id IS UNIQUE;
        CREATE CONSTRAINT movie_id_unique FOR (n:Movie) REQUIRE n.movie_id IS UNIQUE;
        CREATE INDEX movie_title_idx FOR (n:Movie) ON (n.title);
        CREATE CONSTRAINT user_id_unique FOR (n:User) REQUIRE n.user_id IS UNIQUE;
        CREATE CONSTRAINT review_id_unique FOR (n:Review) REQUIRE n.review_id IS UNIQUE;
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.GRAPH, "neo4j"), schema, script))

        assert result.status == ValidationStatus.PASS
        assert "movie.director_id" in result.details
        assert "review.movie_id" in result.details
        assert "review.user_id" in result.details

    def test_skips_self_referencing_fk(self):
        schema = _schema(
            entities=[
                _entity("comment", [
                    _attr("comment_id", pk=True),
                    _attr("parent_comment_id", indexed=True),
                    _attr("content"),
                ]),
            ],
            relationships=[
                Relationship(
                    name="comment_replies",
                    source_entity="comment",
                    target_entity="comment",
                    cardinality=RelationshipCardinality.ONE_TO_MANY,
                ),
            ],
        )
        script = """
        CREATE CONSTRAINT comment_id_unique FOR (n:Comment) REQUIRE n.comment_id IS UNIQUE;
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.GRAPH, "neo4j"), schema, script))

        assert result.status == ValidationStatus.PASS
        assert "comment.parent_comment_id" in result.details


class TestRelationshipAttributes:
    def test_passes_mn_relationship_attributes_present(self):
        schema = _schema(
            entities=[
                _entity("actors", [_attr("id", pk=True)]),
                _entity("movies", [_attr("id", pk=True)]),
            ],
            relationships=[
                Relationship(
                    name="acted_in",
                    source_entity="actors",
                    target_entity="movies",
                    cardinality=RelationshipCardinality.MANY_TO_MANY,
                    attributes=[_attr("role"), _attr("billing_order")],
                ),
            ],
        )
        script = """
        CREATE TABLE actors (id SERIAL PRIMARY KEY);
        CREATE TABLE movies (id SERIAL PRIMARY KEY);
        CREATE TABLE actors_movies (
            actor_id INTEGER REFERENCES actors(id),
            movie_id INTEGER REFERENCES movies(id),
            role VARCHAR(255),
            billing_order INTEGER
        );
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.RELATIONAL), schema, script))

        assert result.status == ValidationStatus.PASS

    def test_fails_mn_relationship_attribute_missing(self):
        schema = _schema(
            entities=[
                _entity("actors", [_attr("id", pk=True)]),
                _entity("movies", [_attr("id", pk=True)]),
            ],
            relationships=[
                Relationship(
                    name="acted_in",
                    source_entity="actors",
                    target_entity="movies",
                    cardinality=RelationshipCardinality.MANY_TO_MANY,
                    attributes=[_attr("role"), _attr("billing_order")],
                ),
            ],
        )
        script = """
        CREATE TABLE actors (id SERIAL PRIMARY KEY);
        CREATE TABLE movies (id SERIAL PRIMARY KEY);
        CREATE TABLE actors_movies (
            actor_id INTEGER REFERENCES actors(id),
            movie_id INTEGER REFERENCES movies(id),
            role VARCHAR(255)
        );
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.RELATIONAL), schema, script))

        assert result.status == ValidationStatus.FAIL
        assert any("billing_order" in t for t in result.todos)


class TestFallback:
    def test_falls_back_to_full_script_when_block_not_parsed(self):
        schema = _schema([
            _entity("sensor_data", [
                _attr("sensor_id", pk=True),
                _attr("temperature"),
            ]),
        ])
        script = """
        -- sensor_data entity
        HSET sensor_data:1 sensor_id "s1" temperature 23.5
        HSET sensor_data:2 sensor_id "s2" temperature 24.1
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.KEY_VALUE, "redis"), schema, script))

        assert result.status == ValidationStatus.PASS

    def test_fails_when_entity_not_mentioned_at_all(self):
        schema = _schema([
            _entity("users", [_attr("id", pk=True)]),
            _entity("orders", [_attr("id", pk=True)]),
        ])
        script = """
        HSET users:1 id "u1"
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.KEY_VALUE, "redis"), schema, script))

        assert result.status == ValidationStatus.FAIL
        assert any("orders" in t for t in result.todos)


class TestTimeSeries:
    def test_passes_hypertable_fields(self):
        schema = _schema([
            _entity("metrics", [
                _attr("time", AbstractDataType.TIMESTAMP),
                _attr("device_id"),
                _attr("temperature"),
            ]),
        ])
        script = """
        CREATE TABLE metrics (
            time TIMESTAMPTZ NOT NULL,
            device_id TEXT NOT NULL,
            temperature DOUBLE PRECISION
        );
        SELECT create_hypertable('metrics', 'time');
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.TIME_SERIES, "timescaledb"), schema, script))

        assert result.status == ValidationStatus.PASS


class TestVector:
    def test_passes_sql_style_vector_db(self):
        schema = _schema([
            _entity("documents", [
                _attr("id", pk=True),
                _attr("content"),
                _attr("embedding"),
            ]),
        ])
        script = """
        CREATE TABLE documents (
            id SERIAL PRIMARY KEY,
            content TEXT,
            embedding vector(768)
        );
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.VECTOR, "pgvector"), schema, script))

        assert result.status == ValidationStatus.PASS

    def test_passes_milvus_python_style(self):
        schema = _schema([
            _entity("movies", [
                _attr("movie_id", pk=True),
                _attr("title"),
                _attr("embedding"),
            ]),
        ])
        script = """
fields = [
    FieldSchema(name="movie_id", dtype=DataType.INT64, is_primary=True),
    FieldSchema(name="title", dtype=DataType.VARCHAR, max_length=500),
    FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=768),
]
schema = CollectionSchema(fields=fields)
collection = Collection("movies", schema)
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.VECTOR, "milvus"), schema, script))

        assert result.status == ValidationStatus.PASS

    def test_passes_weaviate_style(self):
        schema = _schema([
            _entity("Movie", [
                _attr("title"),
                _attr("genre"),
            ]),
        ])
        script = """
class_obj = {
    "class": "Movie",
    "properties": [
        {"name": "title", "dataType": ["text"]},
        {"name": "genre", "dataType": ["text"]}
    ]
}
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.VECTOR, "weaviate"), schema, script))

        assert result.status == ValidationStatus.PASS

    def test_fails_milvus_missing_field(self):
        schema = _schema([
            _entity("movies", [
                _attr("movie_id", pk=True),
                _attr("title"),
                _attr("rating"),
            ]),
        ])
        script = """
fields = [
    FieldSchema(name="movie_id", dtype=DataType.INT64, is_primary=True),
    FieldSchema(name="title", dtype=DataType.VARCHAR, max_length=500),
]
schema = CollectionSchema(fields=fields)
collection = Collection("movies", schema)
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.VECTOR, "milvus"), schema, script))

        assert result.status == ValidationStatus.FAIL
        assert any("rating" in t for t in result.todos)


class TestKeyValue:
    def test_passes_redisearch_ft_create(self):
        schema = _schema([
            _entity("products", [
                _attr("name"),
                _attr("price"),
                _attr("category"),
            ]),
        ])
        script = """
FT.CREATE idx:products ON HASH PREFIX 1 products SCHEMA name TEXT price NUMERIC category TAG;
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.KEY_VALUE, "redis"), schema, script))

        assert result.status == ValidationStatus.PASS

    def test_fails_redisearch_missing_field(self):
        schema = _schema([
            _entity("products", [
                _attr("name"),
                _attr("price"),
                _attr("description"),
            ]),
        ])
        script = """
FT.CREATE idx:products ON HASH PREFIX 1 products SCHEMA name TEXT price NUMERIC;
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.KEY_VALUE, "redis"), schema, script))

        assert result.status == ValidationStatus.FAIL
        assert any("description" in t for t in result.todos)


class TestEmbeddedEdgeCases:
    def test_embedded_parent_not_parsed_but_present_in_script(self):
        schema = _schema([
            _entity("orders", [_attr("total")]),
            _entity("items", [_attr("name"), _attr("qty")]),
        ])
        script = """
        -- orders collection with embedded items
        HSET orders:1 total 100
        HSET orders:1:items name "Widget" qty 3
        """
        mappings = [
            DocumentEmbeddingMapping(entity_name="orders", is_embedded=False),
            DocumentEmbeddingMapping(
                entity_name="items", is_embedded=True,
                parent_entity="orders", field_name="items",
            ),
        ]
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.KEY_VALUE, "redis"), schema, script, mappings))

        assert result.status == ValidationStatus.PASS

    def test_embedded_parent_not_found_in_script(self):
        schema = _schema([
            _entity("items", [_attr("name")]),
        ])
        script = "-- empty script"
        mappings = [
            DocumentEmbeddingMapping(
                entity_name="items", is_embedded=True,
                parent_entity="orders", field_name="items",
            ),
        ]
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.KEY_VALUE, "redis"), schema, script, mappings))

        assert result.status == ValidationStatus.FAIL
        assert any("orders" in t and "Parent" in t for t in result.todos)


class TestRelationshipEdgeCases:
    def test_skips_non_mn_relationships(self):
        schema = _schema(
            entities=[
                _entity("users", [_attr("id", pk=True)]),
                _entity("posts", [_attr("id", pk=True)]),
            ],
            relationships=[
                Relationship(
                    name="user_posts",
                    source_entity="users",
                    target_entity="posts",
                    cardinality=RelationshipCardinality.ONE_TO_MANY,
                    attributes=[_attr("extra")],
                ),
            ],
        )
        script = """
        CREATE TABLE users (id SERIAL PRIMARY KEY);
        CREATE TABLE posts (id SERIAL PRIMARY KEY);
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.RELATIONAL), schema, script))

        assert result.status == ValidationStatus.PASS

    def test_skips_mn_with_empty_attributes(self):
        schema = _schema(
            entities=[
                _entity("users", [_attr("id", pk=True)]),
                _entity("tags", [_attr("id", pk=True)]),
            ],
            relationships=[
                Relationship(
                    name="user_tags",
                    source_entity="users",
                    target_entity="tags",
                    cardinality=RelationshipCardinality.MANY_TO_MANY,
                ),
            ],
        )
        script = """
        CREATE TABLE users (id SERIAL PRIMARY KEY);
        CREATE TABLE tags (id SERIAL PRIMARY KEY);
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.RELATIONAL), schema, script))

        assert result.status == ValidationStatus.PASS

    def test_skips_unconstrained_graph_mn_attributes(self):
        schema = _schema(
            entities=[
                _entity("actors", [_attr("actor_id", pk=True)]),
                _entity("movies", [_attr("movie_id", pk=True)]),
            ],
            relationships=[
                Relationship(
                    name="acted_in",
                    source_entity="actors",
                    target_entity="movies",
                    cardinality=RelationshipCardinality.MANY_TO_MANY,
                    attributes=[_attr("role"), _attr("billing_order")],
                ),
            ],
        )
        script = """
        CREATE CONSTRAINT actor_id_unique FOR (n:Actors) REQUIRE n.actor_id IS UNIQUE;
        CREATE CONSTRAINT movie_id_unique FOR (n:Movies) REQUIRE n.movie_id IS UNIQUE;
        """
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.GRAPH, "neo4j"), schema, script))

        assert result.status == ValidationStatus.PASS


class TestGraphEdgeCases:
    def test_graph_block_without_trailing_semicolon(self):
        schema = _schema([
            _entity("user", [_attr("user_id", pk=True)]),
        ])
        script = "CREATE CONSTRAINT user_id FOR (n:User) REQUIRE n.user_id IS UNIQUE"
        checker = FieldCoverageChecker()
        result = checker.validate(_ctx(_target(DatabaseType.GRAPH, "neo4j"), schema, script))

        assert result.status == ValidationStatus.PASS


class TestHelpers:
    def test_extract_paren_block_invalid_position(self):
        checker = FieldCoverageChecker()
        assert checker._extract_paren_block("abc", 5) is None
        assert checker._extract_paren_block("abc", 0) is None

    def test_extract_paren_block_unmatched(self):
        checker = FieldCoverageChecker()
        assert checker._extract_paren_block("(abc", 0) is None

    def test_extract_brace_block_invalid_position(self):
        checker = FieldCoverageChecker()
        assert checker._extract_brace_block("abc", 5) is None
        assert checker._extract_brace_block("abc", 0) is None

    def test_extract_brace_block_unmatched(self):
        checker = FieldCoverageChecker()
        assert checker._extract_brace_block("{abc", 0) is None

    def test_extract_brace_block_valid(self):
        checker = FieldCoverageChecker()
        assert checker._extract_brace_block("{hello}", 0) == "hello"

    def test_snake_to_pascal(self):
        checker = FieldCoverageChecker()
        assert checker._snake_to_pascal("movie_review") == "MovieReview"
        assert checker._snake_to_pascal("user") == "User"


class TestGetGraphFkAttributes:
    def test_returns_empty_frozenset_when_entity_not_in_schema(self):
        schema = _schema([_entity("other", [_attr("id", AbstractDataType.INTEGER, pk=True)])])
        result = FieldCoverageChecker._get_graph_fk_attributes("nonexistent", schema)
        assert result == frozenset()

    def test_skips_attr_with_empty_stem_after_stripping_id_suffix(self):
        # "id" (not pk): ends with "id", stem = "id"[:-2] = "" → skipped
        schema = _schema([
            _entity("user", [
                _attr("user_id", AbstractDataType.INTEGER, pk=True),
                _attr("id"),
            ]),
        ])
        result = FieldCoverageChecker._get_graph_fk_attributes("user", schema)
        assert "id" not in result

    def test_self_reference_stem_marks_attr_as_fk(self):
        # entity "comment", attr "comment_id" (not pk):
        # stem="comment" == entity_name_norm="comment" → identified as FK
        schema = _schema([
            _entity("comment", [
                _attr("id", AbstractDataType.INTEGER, pk=True),
                _attr("comment_id"),
            ]),
        ])
        result = FieldCoverageChecker._get_graph_fk_attributes("comment", schema)
        assert "comment_id" in result

    def test_skips_related_entity_whose_name_normalizes_to_empty(self):
        # related entity named "_" normalizes to "" → that iteration is skipped
        schema = _schema(
            entities=[
                _entity("item", [
                    _attr("id", AbstractDataType.INTEGER, pk=True),
                    _attr("some_id"),
                ]),
                _entity("_", [_attr("id", AbstractDataType.INTEGER, pk=True)]),
            ],
            relationships=[
                Relationship(
                    name="item_to_placeholder",
                    source_entity="item",
                    target_entity="_",
                    cardinality=RelationshipCardinality.ONE_TO_MANY,
                ),
            ],
        )
        result = FieldCoverageChecker._get_graph_fk_attributes("item", schema)
        assert "some_id" not in result

    def test_partial_stem_match_against_related_entity_marks_as_fk(self):
        # entity "order", attr "admin_id": stem="admin"
        # related entity "system_administrator": "admin" in "systemadministrator" → FK
        schema = _schema(
            entities=[
                _entity("order", [
                    _attr("id", AbstractDataType.INTEGER, pk=True),
                    _attr("admin_id"),
                ]),
                _entity("system_administrator", [
                    _attr("id", AbstractDataType.INTEGER, pk=True),
                ]),
            ],
            relationships=[
                Relationship(
                    name="admin_handles_order",
                    source_entity="system_administrator",
                    target_entity="order",
                    cardinality=RelationshipCardinality.ONE_TO_MANY,
                ),
            ],
        )
        result = FieldCoverageChecker._get_graph_fk_attributes("order", schema)
        assert "admin_id" in result
