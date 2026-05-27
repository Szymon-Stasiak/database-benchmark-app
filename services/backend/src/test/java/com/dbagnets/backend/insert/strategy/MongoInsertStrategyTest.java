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

class MongoInsertStrategyTest {

    private final MongoInsertStrategy strategy = new MongoInsertStrategy(new ObjectMapper());

    @Test
    void singleEmitsInsertOnePerRecord() {
        String script = strategy.buildScript(ctx(InsertMode.SINGLE, 1, threeRecords()));
        assertEquals(3, script.split("insertOne", -1).length - 1);
    }

    @Test
    void batchEmitsInsertManyChunks() {
        String script = strategy.buildScript(ctx(InsertMode.BATCH, 2, threeRecords()));
        assertEquals(2, script.split("insertMany", -1).length - 1, "batchSize=2 over 3 records → 2 insertMany calls");
    }

    @Test
    void bulkEmitsSingleInsertManyWithAll() {
        String script = strategy.buildScript(ctx(InsertMode.BULK, 0, threeRecords()));
        assertEquals(1, script.split("insertMany", -1).length - 1);
        assertTrue(script.contains("\"id\":"));
    }

    private InsertContext ctx(InsertMode mode, int batchSize, List<GeneratedRecord> records) {
        return new InsertContext(
            "c", "mongodb", "8.0", 27017, "User",
            List.of(new LogicalAttribute("id", "uuid", new AttributeConstraints(true, true, false, true), null)),
            records, mode, batchSize
        );
    }

    private List<GeneratedRecord> threeRecords() {
        Map<String, Object> r1 = new LinkedHashMap<>(); r1.put("id", "a");
        Map<String, Object> r2 = new LinkedHashMap<>(); r2.put("id", "b");
        Map<String, Object> r3 = new LinkedHashMap<>(); r3.put("id", "c");
        return List.of(new GeneratedRecord(r1), new GeneratedRecord(r2), new GeneratedRecord(r3));
    }
}
