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

class ArangoInsertStrategyTest {

    private final ArangoInsertStrategy strategy = new ArangoInsertStrategy(new ObjectMapper());

    @Test
    void singleEmitsInsertPerRecord() {
        String s = strategy.buildScript(ctx(InsertMode.SINGLE, 1));
        assertEquals(2, s.split("c.insert\\(\\{", -1).length - 1);
    }

    @Test
    void bulkEmitsSingleInsertArray() {
        String s = strategy.buildScript(ctx(InsertMode.BULK, 0));
        assertEquals(1, s.split("c.insert\\(\\[", -1).length - 1);
    }

    @Test
    void initializesCollection() {
        String s = strategy.buildScript(ctx(InsertMode.BULK, 0));
        assertTrue(s.contains("db._collection('User')"));
    }

    private InsertContext ctx(InsertMode mode, int batchSize) {
        return new InsertContext("c", "arangodb", "3.12", 8529, "User",
            List.of(new LogicalAttribute("id", "uuid", new AttributeConstraints(true, true, false, true), null)),
            twoRecords(), mode, batchSize);
    }

    private List<GeneratedRecord> twoRecords() {
        Map<String, Object> r1 = new LinkedHashMap<>(); r1.put("id", "a");
        Map<String, Object> r2 = new LinkedHashMap<>(); r2.put("id", "b");
        return List.of(new GeneratedRecord(r1), new GeneratedRecord(r2));
    }
}
