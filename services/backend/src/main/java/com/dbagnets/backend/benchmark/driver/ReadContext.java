package com.dbagnets.backend.benchmark.driver;

import com.dbagnets.backend.benchmark.registry.EntityIdRegistry.RegistryEntry;
import com.dbagnets.backend.benchmark.schema.EmbeddingMap;
import com.dbagnets.backend.benchmark.schema.LogicalSchema;

import java.util.List;

public record ReadContext(
        String benchmarkId,
        String databaseId,
        String dbName,
        String dbVersion,
        String hostAddress,
        int hostPort,
        LogicalSchema schema,
        EmbeddingMap embeddings,
        String entityName,
        List<RegistryEntry> targets,
        boolean includeChildren,
        InsertMode mode
) {
    public ReadContext(String benchmarkId, String databaseId, String dbName, String dbVersion,
                       String hostAddress, int hostPort, LogicalSchema schema, EmbeddingMap embeddings,
                       String entityName, List<RegistryEntry> targets, boolean includeChildren) {
        this(benchmarkId, databaseId, dbName, dbVersion, hostAddress, hostPort, schema, embeddings,
                entityName, targets, includeChildren, InsertMode.SINGLE);
    }
}
