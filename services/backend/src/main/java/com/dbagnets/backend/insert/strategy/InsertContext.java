package com.dbagnets.backend.insert.strategy;

import com.dbagnets.backend.insert.datagen.GeneratedRecord;
import com.dbagnets.backend.insert.entity.InsertMode;
import com.dbagnets.backend.insert.schema.LogicalAttribute;

import java.util.List;

/**
 * Everything a {@link DatabaseInsertStrategy} needs to perform an insert.
 *
 * <p>Legacy docker-exec strategies use {@link #containerId()} and ignore {@link #host()} /
 * {@link #port()}. Native-client strategies (JDBC, Bolt, native Mongo driver, etc.) ignore
 * {@code containerId} and connect through {@code host:port} instead — this is what gives Slice C
 * onwards the honest per-DB timing the user asked for.
 *
 * <p>Immutable. Safe to share across the parallel per-DB tasks.
 */
public record InsertContext(
    String containerId,
    String dbName,
    String dbVersion,
    String host,
    Integer hostPort,
    String entityName,
    List<LogicalAttribute> attributes,
    List<GeneratedRecord> records,
    InsertMode mode,
    int batchSize,
    int workerCount
) {
    /** Convenience constructor for callers that don't care about host/port (legacy docker-exec
     *  callers) — host defaults to "localhost". */
    public InsertContext(
        String containerId, String dbName, String dbVersion, Integer hostPort,
        String entityName, List<LogicalAttribute> attributes,
        List<GeneratedRecord> records, InsertMode mode, int batchSize
    ) {
        this(containerId, dbName, dbVersion, "localhost", hostPort,
            entityName, attributes, records, mode, batchSize, 1);
    }

    /** Returns a copy with {@code workerCount} swapped in — used by the orchestrator when
     *  forwarding the user-chosen worker count to native-client strategies. */
    public InsertContext withWorkerCount(int workers) {
        return new InsertContext(containerId, dbName, dbVersion, host, hostPort, entityName,
            attributes, records, mode, batchSize, workers);
    }

    public int effectiveBatchSize() {
        if (batchSize <= 0) return records.size();
        return Math.min(batchSize, records.size());
    }

    public int effectiveWorkerCount() {
        return workerCount <= 0 ? 1 : workerCount;
    }
}
