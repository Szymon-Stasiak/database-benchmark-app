package com.dbagnets.backend.insert.strategy;

import com.dbagnets.backend.docker.DockerService;
import com.dbagnets.backend.insert.datagen.GeneratedRecord;
import com.dbagnets.backend.insert.entity.InsertMode;
import com.dbagnets.backend.insert.schema.AttributeConstraints;
import com.dbagnets.backend.insert.schema.LogicalAttribute;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PostgresInsertStrategyTest {

    private final PostgresInsertStrategy strategy = new PostgresInsertStrategy();

    @Test
    void singleModeEmitsOneInsertPerRecord() {
        DockerService docker = mock(DockerService.class);
        when(docker.execWithStdin(anyString(), anyString(), any(String[].class))).thenReturn("INSERT 0 1\nINSERT 0 1\n");

        InsertContext ctx = ctx(InsertMode.SINGLE, 1, twoRecords());
        InsertOutcome outcome = strategy.insert(docker, ctx);

        assertTrue(outcome.success());
        assertEquals(2, outcome.recordsInserted());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(docker).execWithStdin(eq("c1"), sql.capture(), any(String[].class));
        String script = sql.getValue();
        long inserts = script.lines().filter(l -> l.startsWith("INSERT INTO")).count();
        assertEquals(2, inserts);
    }

    @Test
    void batchModeWrapsBatchInTransaction() {
        DockerService docker = mock(DockerService.class);
        when(docker.execWithStdin(anyString(), anyString(), any(String[].class))).thenReturn("COMMIT\n");

        InsertContext ctx = ctx(InsertMode.BATCH, 1, twoRecords());
        strategy.insert(docker, ctx);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(docker).execWithStdin(eq("c1"), sql.capture(), any(String[].class));
        String script = sql.getValue();
        assertTrue(script.contains("BEGIN;"), "expected BEGIN; in:\n" + script);
        assertTrue(script.contains("COMMIT;"), "expected COMMIT; in:\n" + script);
    }

    @Test
    void bulkModeEmitsSingleMultiValuesInsert() {
        DockerService docker = mock(DockerService.class);
        when(docker.execWithStdin(anyString(), anyString(), any(String[].class))).thenReturn("INSERT 0 2\n");

        InsertContext ctx = ctx(InsertMode.BULK, 0, twoRecords());
        strategy.insert(docker, ctx);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(docker).execWithStdin(eq("c1"), sql.capture(), any(String[].class));
        String script = sql.getValue();
        // exactly one INSERT INTO ... VALUES with two value rows
        assertEquals(1, script.split("INSERT INTO").length - 1);
        assertTrue(script.contains("VALUES"));
    }

    @Test
    void detectsErrorInOutput() {
        DockerService docker = mock(DockerService.class);
        when(docker.execWithStdin(anyString(), anyString(), any(String[].class)))
            .thenReturn("ERROR: relation \"users\" does not exist");

        InsertOutcome outcome = strategy.insert(docker, ctx(InsertMode.SINGLE, 1, twoRecords()));
        assertFalse(outcome.success());
        assertNotNull(outcome.errorMessage());
        assertTrue(outcome.errorMessage().toLowerCase().contains("error"));
    }

    @Test
    void clientCommandIncludesOnErrorStop() {
        InsertContext ctx = ctx(InsertMode.SINGLE, 1, twoRecords());
        String[] cmd = strategy.clientCommand(ctx);
        assertEquals("psql", cmd[0]);
        boolean stopFlag = false;
        for (String s : cmd) if (s.contains("ON_ERROR_STOP=1")) stopFlag = true;
        assertTrue(stopFlag);
    }

    private InsertContext ctx(InsertMode mode, int batchSize, List<GeneratedRecord> records) {
        return new InsertContext(
            "c1", "postgresql", "16", 5432, "User",
            attributes(), records, mode, batchSize
        );
    }

    private List<LogicalAttribute> attributes() {
        return List.of(
            new LogicalAttribute("id", "uuid", new AttributeConstraints(true, true, false, true), null),
            new LogicalAttribute("name", "varchar", new AttributeConstraints(false, false, false, false), null)
        );
    }

    private List<GeneratedRecord> twoRecords() {
        Map<String, Object> r1 = new LinkedHashMap<>();
        r1.put("id", "11111111-1111-1111-1111-111111111111");
        r1.put("name", "Alice");
        Map<String, Object> r2 = new LinkedHashMap<>();
        r2.put("id", "22222222-2222-2222-2222-222222222222");
        r2.put("name", "O'Brien");
        return List.of(new GeneratedRecord(r1), new GeneratedRecord(r2));
    }
}
