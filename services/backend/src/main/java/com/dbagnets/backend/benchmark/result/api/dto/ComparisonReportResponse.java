package com.dbagnets.backend.benchmark.result.api.dto;

import java.time.Instant;
import java.util.List;

public record ComparisonReportResponse(
        String benchmarkId,
        String topic,
        Instant generatedAt,
        List<DatabaseDescriptor> databases,
        List<InsertSummary> insertSummary,
        List<ReadSummary> readSummary,
        List<DeleteSummary> deleteSummary,
        List<RadarScore> radarScores) {

    public record DatabaseDescriptor(
            String databaseId, String dbName, String dbVersion, String engineCategory) {}

    public record InsertSummary(
            String databaseId,
            String dbName,
            int totalRuns,
            long totalRowsInserted,
            Double avgDbTimeMs,
            Double avgWireTimeMs,
            Double avgOverheadMs,
            Double avgThroughputRps,
            long totalConflicts,
            int successCount,
            int failedCount) {}

    public record ReadSummary(
            String databaseId,
            String dbName,
            int totalRuns,
            long totalSamples,
            Double avgP50DbTimeUs,
            Double avgP95DbTimeUs,
            Double avgP99DbTimeUs,
            Double avgMeanDbTimeUs,
            Double avgWireTimeMs,
            int successCount,
            int failedCount) {}

    public record DeleteSummary(
            String databaseId,
            String dbName,
            int totalRuns,
            long totalRowsDeleted,
            Double avgP50DbTimeUs,
            Double avgP95DbTimeUs,
            Double avgP99DbTimeUs,
            Long totalSizeFreedBytes,
            int successCount,
            int failedCount) {}

    public record RadarScore(
            String databaseId,
            String dbName,
            double insertSpeed,
            double readSpeed,
            double deleteSpeed,
            double sizeEfficiency,
            double consistency) {}
}
