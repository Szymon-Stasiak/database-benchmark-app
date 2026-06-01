package com.dbagnets.backend.benchmark.model;

public record DatabaseSizeResponse(
        String databaseId,
        String dbName,
        String dbVersion,
        Long sizeBytes,
        Long baselineBytes,
        Long dataBytes,
        String sizeHuman,
        boolean available
) {
}
