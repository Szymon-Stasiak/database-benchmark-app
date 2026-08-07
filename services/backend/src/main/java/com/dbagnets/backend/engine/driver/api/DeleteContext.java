package com.dbagnets.backend.engine.driver.api;

import com.dbagnets.backend.engine.registry.EntityIdRegistry.RegistryEntry;
import com.dbagnets.backend.engine.schema.EmbeddingMap;
import com.dbagnets.backend.engine.schema.LogicalSchema;

import java.util.List;

public record DeleteContext(String benchmarkId, String databaseId, String dbName, String dbVersion, String hostAddress,
                            int hostPort, LogicalSchema schema, EmbeddingMap embeddings, String entityName,
                            List<RegistryEntry> targets, DeletionMode deletionMode, InsertMode mode) {
    public DeleteContext(String benchmarkId, String databaseId, String dbName, String dbVersion, String hostAddress, int hostPort, LogicalSchema schema, EmbeddingMap embeddings, String entityName, List<RegistryEntry> targets, boolean includeChildren) {
        this(benchmarkId, databaseId, dbName, dbVersion, hostAddress, hostPort, schema, embeddings, entityName, targets, includeChildren ? DeletionMode.WITH_CHILDREN : DeletionMode.NATIVE, InsertMode.SINGLE);
    }

    public boolean includeChildren() {
        return deletionMode == DeletionMode.WITH_CHILDREN;
    }
}