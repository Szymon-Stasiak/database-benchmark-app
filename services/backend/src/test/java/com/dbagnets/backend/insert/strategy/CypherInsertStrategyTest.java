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

class CypherInsertStrategyTest {

    private final CypherInsertStrategy neo4j = new CypherInsertStrategy("neo4j", new ObjectMapper());

    @Test
    void singleEmitsCreatePerRecord() {
        String script = neo4j.buildScript(ctx(InsertMode.SINGLE, 1));
        assertEquals(2, script.split("CREATE \\(:User").length - 1);
    }

    @Test
    void batchEmitsUnwindChunks() {
        String script = neo4j.buildScript(ctx(InsertMode.BATCH, 1));
        assertEquals(2, script.split("UNWIND", -1).length - 1);
    }

    @Test
    void bulkEmitsSingleUnwind() {
        String script = neo4j.buildScript(ctx(InsertMode.BULK, 0));
        assertEquals(1, script.split("UNWIND", -1).length - 1);
        assertTrue(script.contains("CREATE (n:User)"));
    }

    @Test
    void labelStartsWithUpperCase() {
        CypherInsertStrategy s = new CypherInsertStrategy("neo4j", new ObjectMapper());
        InsertContext ctx = new InsertContext("c", "neo4j", "5", 7687, "order_line",
            List.of(new LogicalAttribute("id", "uuid", new AttributeConstraints(true, true, false, true), null)),
            twoRecords(), InsertMode.BULK, 0);
        String script = s.buildScript(ctx);
        assertTrue(script.contains("CREATE (n:Order_line)"));
    }

    private InsertContext ctx(InsertMode mode, int batchSize) {
        return new InsertContext("c", "neo4j", "5", 7687, "User",
            List.of(new LogicalAttribute("id", "uuid", new AttributeConstraints(true, true, false, true), null)),
            twoRecords(), mode, batchSize);
    }

    private List<GeneratedRecord> twoRecords() {
        Map<String, Object> r1 = new LinkedHashMap<>(); r1.put("id", "a");
        Map<String, Object> r2 = new LinkedHashMap<>(); r2.put("id", "b");
        return List.of(new GeneratedRecord(r1), new GeneratedRecord(r2));
    }
}
