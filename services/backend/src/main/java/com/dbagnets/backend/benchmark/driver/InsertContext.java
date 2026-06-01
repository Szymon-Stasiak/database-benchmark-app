package com.dbagnets.backend.benchmark.driver;

import com.dbagnets.backend.benchmark.cascade.CascadePlan;
import com.dbagnets.backend.benchmark.datagen.GeneratedRow;
import com.dbagnets.backend.benchmark.schema.EmbeddingMap;
import com.dbagnets.backend.benchmark.schema.LogicalSchema;

import java.util.List;
import java.util.Map;

public record InsertContext(
        String benchmarkId,
        String databaseId,
        String dbName,
        String dbVersion,
        String hostAddress,
        int hostPort,
        LogicalSchema schema,
        EmbeddingMap embeddings,
        CascadePlan plan,
        Map<String, List<GeneratedRow>> rowsByEntity,
        InsertMode mode,
        int batchSize,
        BatchProgress progress
) {
}
