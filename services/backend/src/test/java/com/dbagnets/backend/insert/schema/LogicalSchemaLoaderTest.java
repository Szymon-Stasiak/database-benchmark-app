package com.dbagnets.backend.insert.schema;

import com.dbagnets.backend.entity.Benchmark;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LogicalSchemaLoaderTest {

    private final LogicalSchemaLoader loader = new LogicalSchemaLoader(new ObjectMapper());

    @Test
    void nullSchemaReturnsEmpty() {
        Benchmark benchmark = new Benchmark("topic", "user@example.com", 4);
        assertEquals(Optional.empty(), loader.load(benchmark));
    }

    @Test
    void parsesFullSchema() {
        Benchmark benchmark = new Benchmark("topic", "user@example.com", 4);
        benchmark.setLogicalSchema("""
            {
              "idea": "shop",
              "depth": 4,
              "depth_chain": ["a","b"],
              "entities": [
                {"name":"User","description":"u","attributes":[
                   {"name":"id","data_type":"uuid","constraints":{"is_primary_key":true,"is_unique":true,"is_nullable":false,"is_indexed":true}}
                ]}
              ],
              "relationships": [],
              "data_size_hints": [{"entity_name":"User","expected_row_count":1000}]
            }
            """);
        LogicalSchema schema = loader.load(benchmark).orElseThrow();
        assertEquals(4, schema.depth());
        assertEquals(1, schema.entitiesOrEmpty().size());
        assertTrue(schema.findEntity("User").isPresent());
        assertTrue(schema.findEntity("user").isPresent(), "lookup is case-insensitive");
        assertTrue(schema.findEntity("Missing").isEmpty());
    }

    @Test
    void unknownFieldsAreIgnored() {
        Benchmark benchmark = new Benchmark("topic", "user@example.com", 4);
        benchmark.setLogicalSchema("""
            {"idea":"x","depth":1,"entities":[],"unknown":42}
            """);
        assertDoesNotThrow(() -> loader.load(benchmark));
    }

    @Test
    void malformedJsonThrows() {
        Benchmark benchmark = new Benchmark("topic", "user@example.com", 4);
        benchmark.setLogicalSchema("not json");
        assertThrows(IllegalStateException.class, () -> loader.load(benchmark));
    }
}
