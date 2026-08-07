package com.dbagnets.backend.benchmark.result.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;

import com.dbagnets.backend.benchmark.result.api.dto.ComparisonReportResponse;
import com.dbagnets.backend.benchmark.result.api.dto.ComparisonReportResponse.DatabaseDescriptor;
import com.dbagnets.backend.benchmark.result.api.dto.ComparisonReportResponse.DeleteSummary;
import com.dbagnets.backend.benchmark.result.api.dto.ComparisonReportResponse.InsertSummary;
import com.dbagnets.backend.benchmark.result.api.dto.ComparisonReportResponse.RadarScore;
import com.dbagnets.backend.benchmark.result.api.dto.ComparisonReportResponse.ReadSummary;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkResult;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkRun;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkRunRepository;
import com.dbagnets.backend.benchmark.run.persistence.OperationType;
import com.dbagnets.backend.benchmark.run.persistence.RunStatus;
import com.dbagnets.backend.infrastructure.persistence.BenchmarkRepository;
import com.dbagnets.backend.shared.entity.Benchmark;
import com.dbagnets.backend.shared.entity.BenchmarkDatabase;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ComparisonReportService {

    private final BenchmarkRepository benchmarkRepository;
    private final BenchmarkRunRepository runRepository;

    public ComparisonReportResponse build(String benchmarkId) {
        Benchmark benchmark =
                benchmarkRepository
                        .findById(benchmarkId)
                        .orElseThrow(
                                () ->
                                        new NoSuchElementException(
                                                "Benchmark not found: " + benchmarkId));

        List<BenchmarkDatabase> databases = benchmark.getDatabases();
        List<DatabaseDescriptor> descriptors =
                databases.stream()
                        .map(
                                db ->
                                        new DatabaseDescriptor(
                                                db.getId(),
                                                db.getDbName(),
                                                db.getDbVersion(),
                                                db.getDbType().name()))
                        .toList();

        List<BenchmarkRun> allRuns =
                runRepository.findByBenchmarkIdOrderByCreatedAtDesc(benchmarkId);

        List<InsertSummary> insertSummaries =
                buildInsertSummaries(databases, filterByType(allRuns, OperationType.INSERT));
        List<ReadSummary> readSummaries =
                buildReadSummaries(databases, filterByType(allRuns, OperationType.READ));
        List<DeleteSummary> deleteSummaries =
                buildDeleteSummaries(databases, filterByType(allRuns, OperationType.DELETE));

        List<RadarScore> radar =
                buildRadarScores(databases, insertSummaries, readSummaries, deleteSummaries);

        return new ComparisonReportResponse(
                benchmarkId,
                benchmark.getTopic(),
                Instant.now(),
                descriptors,
                insertSummaries,
                readSummaries,
                deleteSummaries,
                radar);
    }

    private List<BenchmarkRun> filterByType(List<BenchmarkRun> runs, OperationType type) {
        return runs.stream().filter(r -> r.getOperationType() == type).toList();
    }

    private List<InsertSummary> buildInsertSummaries(
            List<BenchmarkDatabase> databases, List<BenchmarkRun> runs) {
        Map<String, List<BenchmarkResult>> perDb = groupResultsByDatabase(runs);
        List<InsertSummary> out = new ArrayList<>();
        for (BenchmarkDatabase db : databases) {
            List<BenchmarkResult> results = perDb.getOrDefault(db.getId(), List.of());
            long totalRows = sumLong(results, BenchmarkResult::getRowsAffected);
            long conflicts =
                    results.stream()
                            .mapToInt(
                                    r ->
                                            r.getConflictsSkipped() == null
                                                    ? 0
                                                    : r.getConflictsSkipped())
                            .sum();
            int success = countStatus(results, RunStatus.SUCCESS);
            int failed = countStatus(results, RunStatus.FAILED);
            Double avgDbMs = averageNs(results, BenchmarkResult::getDbTimeNs, 1_000_000.0);
            Double avgWireMs = averageNs(results, BenchmarkResult::getWireTimeNs, 1_000_000.0);
            Double avgOverheadMs = averageNs(results, BenchmarkResult::getOverheadNs, 1_000_000.0);
            Double avgThroughput = averageThroughput(results);
            out.add(
                    new InsertSummary(
                            db.getId(),
                            db.getDbName(),
                            results.size(),
                            totalRows,
                            avgDbMs,
                            avgWireMs,
                            avgOverheadMs,
                            avgThroughput,
                            conflicts,
                            success,
                            failed));
        }
        return out;
    }

    private List<ReadSummary> buildReadSummaries(
            List<BenchmarkDatabase> databases, List<BenchmarkRun> runs) {
        Map<String, List<BenchmarkResult>> perDb = groupResultsByDatabase(runs);
        List<ReadSummary> out = new ArrayList<>();
        for (BenchmarkDatabase db : databases) {
            List<BenchmarkResult> results = perDb.getOrDefault(db.getId(), List.of());
            long totalSamples =
                    results.stream()
                            .mapToLong(
                                    r ->
                                            r.getSamplesRecorded() == null
                                                    ? 0L
                                                    : r.getSamplesRecorded())
                            .sum();
            int success = countStatus(results, RunStatus.SUCCESS);
            int failed = countStatus(results, RunStatus.FAILED);
            out.add(
                    new ReadSummary(
                            db.getId(),
                            db.getDbName(),
                            results.size(),
                            totalSamples,
                            averageNs(results, BenchmarkResult::getP50DbTimeNs, 1_000.0),
                            averageNs(results, BenchmarkResult::getP95DbTimeNs, 1_000.0),
                            averageNs(results, BenchmarkResult::getP99DbTimeNs, 1_000.0),
                            averageNs(results, BenchmarkResult::getMeanDbTimeNs, 1_000.0),
                            averageNs(results, BenchmarkResult::getWireTimeNs, 1_000_000.0),
                            success,
                            failed));
        }
        return out;
    }

    private List<DeleteSummary> buildDeleteSummaries(
            List<BenchmarkDatabase> databases, List<BenchmarkRun> runs) {
        Map<String, List<BenchmarkResult>> perDb = groupResultsByDatabase(runs);
        List<DeleteSummary> out = new ArrayList<>();
        for (BenchmarkDatabase db : databases) {
            List<BenchmarkResult> results = perDb.getOrDefault(db.getId(), List.of());
            long totalRows = sumLong(results, BenchmarkResult::getRowsAffected);
            long sizeFreed =
                    results.stream()
                            .filter(
                                    r ->
                                            r.getDataSizeBefore() != null
                                                    && r.getDataSizeAfter() != null)
                            .mapToLong(
                                    r -> Math.max(0L, r.getDataSizeBefore() - r.getDataSizeAfter()))
                            .sum();
            int success = countStatus(results, RunStatus.SUCCESS);
            int failed = countStatus(results, RunStatus.FAILED);
            out.add(
                    new DeleteSummary(
                            db.getId(),
                            db.getDbName(),
                            results.size(),
                            totalRows,
                            averageNs(results, BenchmarkResult::getP50DbTimeNs, 1_000.0),
                            averageNs(results, BenchmarkResult::getP95DbTimeNs, 1_000.0),
                            averageNs(results, BenchmarkResult::getP99DbTimeNs, 1_000.0),
                            sizeFreed > 0 ? sizeFreed : null,
                            success,
                            failed));
        }
        return out;
    }

    private List<RadarScore> buildRadarScores(
            List<BenchmarkDatabase> databases,
            List<InsertSummary> inserts,
            List<ReadSummary> reads,
            List<DeleteSummary> deletes) {
        Map<String, Double> insertRpsByDb = new HashMap<>();
        inserts.forEach(s -> insertRpsByDb.put(s.databaseId(), nullSafe(s.avgThroughputRps())));
        Map<String, Double> readP50ByDb = new HashMap<>();
        reads.forEach(s -> readP50ByDb.put(s.databaseId(), nullSafe(s.avgP50DbTimeUs())));
        Map<String, Double> deleteP50ByDb = new HashMap<>();
        deletes.forEach(s -> deleteP50ByDb.put(s.databaseId(), nullSafe(s.avgP50DbTimeUs())));
        Map<String, Double> sizeFreedByDb = new HashMap<>();
        deletes.forEach(
                s ->
                        sizeFreedByDb.put(
                                s.databaseId(),
                                s.totalSizeFreedBytes() == null
                                        ? 0.0
                                        : (double) s.totalSizeFreedBytes()));
        Map<String, Double> consistencyByDb = new HashMap<>();
        for (BenchmarkDatabase db : databases) {
            int success = totalSuccessFor(db.getId(), inserts, reads, deletes);
            int total = totalRunsFor(db.getId(), inserts, reads, deletes);
            consistencyByDb.put(db.getId(), total == 0 ? 0.0 : (success * 100.0 / total));
        }

        List<RadarScore> out = new ArrayList<>();
        for (BenchmarkDatabase db : databases) {
            out.add(
                    new RadarScore(
                            db.getId(),
                            db.getDbName(),
                            normalizeHigherBetter(insertRpsByDb, db.getId()),
                            normalizeLowerBetter(readP50ByDb, db.getId()),
                            normalizeLowerBetter(deleteP50ByDb, db.getId()),
                            normalizeHigherBetter(sizeFreedByDb, db.getId()),
                            consistencyByDb.getOrDefault(db.getId(), 0.0)));
        }
        return out;
    }

    private int totalSuccessFor(
            String dbId, List<InsertSummary> i, List<ReadSummary> r, List<DeleteSummary> d) {
        int sum = 0;
        for (InsertSummary s : i) if (s.databaseId().equals(dbId)) sum += s.successCount();
        for (ReadSummary s : r) if (s.databaseId().equals(dbId)) sum += s.successCount();
        for (DeleteSummary s : d) if (s.databaseId().equals(dbId)) sum += s.successCount();
        return sum;
    }

    private int totalRunsFor(
            String dbId, List<InsertSummary> i, List<ReadSummary> r, List<DeleteSummary> d) {
        int sum = 0;
        for (InsertSummary s : i) if (s.databaseId().equals(dbId)) sum += s.totalRuns();
        for (ReadSummary s : r) if (s.databaseId().equals(dbId)) sum += s.totalRuns();
        for (DeleteSummary s : d) if (s.databaseId().equals(dbId)) sum += s.totalRuns();
        return sum;
    }

    private Map<String, List<BenchmarkResult>> groupResultsByDatabase(List<BenchmarkRun> runs) {
        Map<String, List<BenchmarkResult>> map = new HashMap<>();
        for (BenchmarkRun run : runs) {
            for (BenchmarkResult result : run.getResults()) {
                map.computeIfAbsent(result.getDatabaseId(), k -> new ArrayList<>()).add(result);
            }
        }
        return map;
    }

    private long sumLong(
            List<BenchmarkResult> results,
            java.util.function.Function<BenchmarkResult, Long> getter) {
        long sum = 0L;
        for (BenchmarkResult r : results) {
            Long value = getter.apply(r);
            if (value != null) sum += value;
        }
        return sum;
    }

    private int countStatus(List<BenchmarkResult> results, RunStatus status) {
        return (int) results.stream().filter(r -> r.getStatus() == status).count();
    }

    private Double averageNs(
            List<BenchmarkResult> results,
            java.util.function.Function<BenchmarkResult, Long> getter,
            double divisor) {
        List<Long> values =
                results.stream().map(getter).filter(java.util.Objects::nonNull).toList();
        if (values.isEmpty()) return null;
        double sum = 0.0;
        for (Long v : values) sum += v;
        return (sum / values.size()) / divisor;
    }

    private Double averageThroughput(List<BenchmarkResult> results) {
        List<Double> values = new ArrayList<>();
        for (BenchmarkResult r : results) {
            Long dur = r.durationMs();
            Long rows = r.getRowsAffected();
            if (dur != null && dur > 0L && rows != null) {
                values.add(rows * 1000.0 / dur);
            }
        }
        if (values.isEmpty()) return null;
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private double nullSafe(Double v) {
        return v == null ? 0.0 : v;
    }

    private double normalizeHigherBetter(Map<String, Double> values, String key) {
        double max = values.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        if (max <= 0.0) return 0.0;
        return values.getOrDefault(key, 0.0) / max * 100.0;
    }

    private double normalizeLowerBetter(Map<String, Double> values, String key) {
        double self = values.getOrDefault(key, 0.0);
        if (self <= 0.0) return 0.0;
        double min =
                values.values().stream()
                        .filter(v -> v > 0.0)
                        .mapToDouble(Double::doubleValue)
                        .min()
                        .orElse(0.0);
        if (min <= 0.0) return 0.0;
        return Math.min(100.0, (min / self) * 100.0);
    }
}
