package com.dbagnets.backend.benchmark.bundle;

import java.time.Instant;
import java.util.List;

public record BundleManifest(
        int version,
        String topic,
        int depth,
        Instant createdAt,
        List<DatabaseEntry> databases
) {
    public static final int CURRENT_VERSION = 1;

    public record DatabaseEntry(
            String dbType,
            String dbName,
            String dbVersion,
            String dockerImage,
            String scriptFile,
            String embeddingMappingsFile
    ) {}
}
