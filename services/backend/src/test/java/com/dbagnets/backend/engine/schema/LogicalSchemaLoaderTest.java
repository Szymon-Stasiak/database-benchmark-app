package com.dbagnets.backend.engine.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class LogicalSchemaLoaderTest {

    private LogicalSchemaLoader loader;

    @BeforeEach
    void setUp() {
        loader = new LogicalSchemaLoader(new ObjectMapper());
    }

    @Test
    void parsesMinimalSchema() {
        String json =
                """
            {
              "idea": "movies",
              "depth": 2,
              "entities": [
                {
                  "name": "Movie",
                  "attributes": [
                    {"name": "movie_id", "data_type": "uuid",
                      "constraints": {"is_primary_key": true, "is_unique": true, "is_nullable": false, "is_indexed": false}},
                    {"name": "title", "data_type": "string",
                      "constraints": {"is_primary_key": false, "is_unique": false, "is_nullable": false, "is_indexed": false}}
                  ]
                }
              ],
              "relationships": []
            }
            """;
        LogicalSchema schema = loader.parse(json);

        assertThat(schema.entities()).hasSize(1);
        LogicalEntity movie = schema.requireEntity("Movie");
        assertThat(movie.primaryKey()).isPresent();
        assertThat(movie.primaryKey().get().name()).isEqualTo("movie_id");
        assertThat(movie.findAttribute("title")).isPresent();
    }

    @Test
    void parsesRelationshipsWithCardinality() {
        String json =
                """
            {
              "idea": "t",
              "depth": 1,
              "entities": [
                {"name": "A", "attributes": []},
                {"name": "B", "attributes": []}
              ],
              "relationships": [
                {"name": "a_has_b", "source_entity": "A", "target_entity": "B", "cardinality": "1:N", "attributes": []}
              ]
            }
            """;
        LogicalSchema schema = loader.parse(json);

        assertThat(schema.relationships()).hasSize(1);
        assertThat(schema.relationships().get(0).cardinality())
                .isEqualTo(RelationshipCardinality.ONE_TO_MANY);
        assertThat(schema.relationshipsTargeting("B")).hasSize(1);
    }

    @Test
    void rejectsEmptyJson() {
        assertThatThrownBy(() -> loader.parse(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }
}
