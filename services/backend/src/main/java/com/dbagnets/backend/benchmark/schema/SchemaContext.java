package com.dbagnets.backend.benchmark.schema;

import java.util.Map;

public record SchemaContext(
        LogicalSchema schema,
        Map<String, EmbeddingMap> embeddingsByDatabaseId
) {
    public EmbeddingMap embeddingsFor(String databaseId) {
        return embeddingsByDatabaseId.getOrDefault(databaseId, EmbeddingMap.empty());
    }
}
