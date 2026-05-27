package com.dbagnets.backend.insert.service;

import com.dbagnets.backend.entity.Benchmark;
import com.dbagnets.backend.insert.schema.LogicalSchemaLoader;
import com.dbagnets.backend.repository.BenchmarkRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EntityCatalogServiceTest {

    private final BenchmarkRepository benchmarkRepository = mock(BenchmarkRepository.class);
    private final LogicalSchemaLoader loader = new LogicalSchemaLoader(new ObjectMapper());
    private final EntityCatalogService service = new EntityCatalogService(benchmarkRepository, loader);

    @Test
    void returnsEntityChoicesFromSchema() {
        Benchmark benchmark = new Benchmark("topic", "u@example.com", 4);
        benchmark.setLogicalSchema("""
            {"idea":"x","depth":4,"entities":[
              {"name":"User","description":"u","attributes":[
                {"name":"id","data_type":"uuid","constraints":{"is_primary_key":true,"is_unique":true,"is_nullable":false,"is_indexed":true}},
                {"name":"email","data_type":"varchar","constraints":{"is_primary_key":false,"is_unique":true,"is_nullable":false,"is_indexed":false}}
              ]}
            ],"relationships":[],"data_size_hints":[]}
            """);
        when(benchmarkRepository.findById("b1")).thenReturn(Optional.of(benchmark));

        var choices = service.listEntities("b1");
        assertEquals(1, choices.size());
        assertEquals("User", choices.get(0).name());
        assertEquals(2, choices.get(0).attributes().size());
        assertTrue(choices.get(0).attributes().get(0).primaryKey());
    }

    @Test
    void returnsEmptyListWhenSchemaMissing() {
        Benchmark benchmark = new Benchmark("topic", "u@example.com", 4);
        when(benchmarkRepository.findById("b1")).thenReturn(Optional.of(benchmark));
        assertTrue(service.listEntities("b1").isEmpty());
    }

    @Test
    void missingBenchmarkThrows() {
        when(benchmarkRepository.findById("missing")).thenReturn(Optional.empty());
        assertThrows(NoSuchElementException.class, () -> service.listEntities("missing"));
    }
}
