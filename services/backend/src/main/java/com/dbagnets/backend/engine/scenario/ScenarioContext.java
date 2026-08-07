package com.dbagnets.backend.engine.scenario;

import com.dbagnets.backend.engine.schema.EmbeddingMap;
import com.dbagnets.backend.engine.schema.LogicalSchema;

public record ScenarioContext(String benchmarkId, String databaseId, String dbName, String dbVersion,
                              String hostAddress, int hostPort, LogicalSchema schema, EmbeddingMap embeddings,
                              ScenarioParams params) {
    public ScenarioType type() {
        return params.type();
    }
}