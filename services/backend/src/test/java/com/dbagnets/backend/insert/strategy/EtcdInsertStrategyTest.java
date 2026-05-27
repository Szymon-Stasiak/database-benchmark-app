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

class EtcdInsertStrategyTest {

    private final EtcdInsertStrategy strategy = new EtcdInsertStrategy(new ObjectMapper());

    @Test
    void buildsShellScriptWithPutPerRecord() {
        String script = strategy.buildShellScript(ctx());
        long puts = script.lines().filter(l -> l.startsWith("etcdctl put")).count();
        assertEquals(2, puts);
        assertTrue(script.contains("ETCDCTL_API=3"));
        assertTrue(script.contains("user/"));
    }

    private InsertContext ctx() {
        return new InsertContext("c", "etcd", "3.5", 2379, "User",
            List.of(new LogicalAttribute("id", "uuid", new AttributeConstraints(true, true, false, true), null)),
            twoRecords(), InsertMode.BATCH, 1);
    }

    private List<GeneratedRecord> twoRecords() {
        Map<String, Object> r1 = new LinkedHashMap<>(); r1.put("id", "a");
        Map<String, Object> r2 = new LinkedHashMap<>(); r2.put("id", "b");
        return List.of(new GeneratedRecord(r1), new GeneratedRecord(r2));
    }
}
