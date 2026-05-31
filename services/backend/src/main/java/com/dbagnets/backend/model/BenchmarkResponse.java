package com.dbagnets.backend.model;

import com.dbagnets.backend.entity.Benchmark;
import com.dbagnets.backend.entity.BenchmarkDatabase;

import java.time.Instant;
import java.util.List;

public record BenchmarkResponse(
        String id,
        String topic,
        String status,
        Instant createdAt,
        String logicalSchema,
        List<DatabaseResponse> databases
) {
    public record DatabaseResponse(
            String id,
            String dbType,
            String dbName,
            String dbVersion,
            String status,
            Integer hostPort,
            String errorMessage
    ) {
        public static DatabaseResponse from(BenchmarkDatabase db) {
            return new DatabaseResponse(
                    db.getId(),
                    db.getDbType().name(),
                    db.getDbName(),
                    db.getDbVersion(),
                    db.getStatus().name(),
                    db.getHostPort(),
                    db.getErrorMessage()
            );
        }
    }

    public static BenchmarkResponse from(Benchmark benchmark) {
        return new BenchmarkResponse(
                benchmark.getId(),
                benchmark.getTopic(),
                benchmark.getStatus().name(),
                benchmark.getCreatedAt(),
                benchmark.getLogicalSchema(),
                benchmark.getDatabases().stream()
                        .map(DatabaseResponse::from)
                        .toList()
        );
    }
}