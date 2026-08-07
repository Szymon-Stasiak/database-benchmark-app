package com.dbagnets.backend.engine.driver.api;

import com.dbagnets.backend.engine.registry.EntityIdRegistry.RegistryEntry;
import com.dbagnets.backend.engine.schema.EmbeddingMap;
import com.dbagnets.backend.engine.schema.LogicalSchema;

import java.util.List;

public record ReadContext(String benchmarkId, String databaseId, String dbName, String dbVersion, String hostAddress,
                          int hostPort, LogicalSchema schema, EmbeddingMap embeddings, String entityName,
                          List<RegistryEntry> targets, ReadDepth readDepth, InsertMode mode) {
    public ReadContext(String benchmarkId, String databaseId, String dbName, String dbVersion, String hostAddress, int hostPort, LogicalSchema schema, EmbeddingMap embeddings, String entityName, List<RegistryEntry> targets, boolean includeChildren) {
        this(benchmarkId, databaseId, dbName, dbVersion, hostAddress, hostPort, schema, embeddings, entityName, targets, ReadDepth.fromIncludeChildren(includeChildren), InsertMode.SINGLE);
    }

    public ReadContext(String benchmarkId, String databaseId, String dbName, String dbVersion, String hostAddress, int hostPort, LogicalSchema schema, EmbeddingMap embeddings, String entityName, List<RegistryEntry> targets, boolean includeChildren, InsertMode mode) {
        this(benchmarkId, databaseId, dbName, dbVersion, hostAddress, hostPort, schema, embeddings, entityName, targets, ReadDepth.fromIncludeChildren(includeChildren), mode);
    }

    public boolean includeChildren() {
        return readDepth != null && readDepth != ReadDepth.NONE;
    }
}