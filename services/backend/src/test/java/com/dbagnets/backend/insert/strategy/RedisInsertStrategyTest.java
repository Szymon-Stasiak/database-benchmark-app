package com.dbagnets.backend.insert.strategy;

import com.dbagnets.backend.insert.datagen.GeneratedRecord;
import com.dbagnets.backend.insert.entity.InsertMode;
import com.dbagnets.backend.insert.schema.AttributeConstraints;
import com.dbagnets.backend.insert.schema.LogicalAttribute;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisInsertStrategyTest {

    private final RedisInsertStrategy strategy = new RedisInsertStrategy(new ObjectMapper());

    @Test
    void singleEmitsSetPerRecord() {
        String script = strategy.buildScript(ctx(InsertMode.SINGLE, 1));
        long sets = script.lines().filter(l -> l.startsWith("SET ")).count();
        assertEquals(3, sets);
    }

    @Test
    void batchEmitsMsetChunks() {
        String script = strategy.buildScript(ctx(InsertMode.BATCH, 2));
        long msets = script.lines().filter(l -> l.startsWith("MSET")).count();
        assertEquals(2, msets);
    }

    @Test
    void bulkEmitsOneMset() {
        String script = strategy.buildScript(ctx(InsertMode.BULK, 0));
        long msets = script.lines().filter(l -> l.startsWith("MSET")).count();
        assertEquals(1, msets);
        assertTrue(script.contains("user:"));
    }

    private InsertContext ctx(InsertMode mode, int batchSize) {
        return new InsertContext("c", "redis", "7", 6379, "User",
            List.of(new LogicalAttribute("id", "uuid", new AttributeConstraints(true, true, false, true), null)),
            threeRecords(), mode, batchSize);
    }

    private List<GeneratedRecord> threeRecords() {
        Map<String, Object> r1 = new LinkedHashMap<>(); r1.put("id", "a");
        Map<String, Object> r2 = new LinkedHashMap<>(); r2.put("id", "b");
        Map<String, Object> r3 = new LinkedHashMap<>(); r3.put("id", "c");
        return List.of(new GeneratedRecord(r1), new GeneratedRecord(r2), new GeneratedRecord(r3));
    }
}
