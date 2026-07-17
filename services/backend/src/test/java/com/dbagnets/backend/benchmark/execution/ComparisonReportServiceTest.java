package com.dbagnets.backend.benchmark.execution;

import com.dbagnets.backend.benchmark.model.ComparisonReportResponse;
import com.dbagnets.backend.entity.Benchmark;
import com.dbagnets.backend.entity.BenchmarkDatabase;
import com.dbagnets.backend.domain.DatabaseType;
import com.dbagnets.backend.repository.BenchmarkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComparisonReportServiceTest {

    private static final String BENCHMARK_ID = "bench-1";

    @Mock private BenchmarkRepository benchmarkRepository;
    @Mock private BenchmarkRunRepository runRepository;

    @InjectMocks
    private ComparisonReportService service;

    private BenchmarkDatabase pg;
    private BenchmarkDatabase mongo;
    private BenchmarkDatabase neo4j;

    @BeforeEach
    void setUp() {
        pg = mockDb("db-pg", "postgresql", DatabaseType.RELATIONAL);
        mongo = mockDb("db-mongo", "mongodb", DatabaseType.DOCUMENT);
        neo4j = mockDb("db-neo4j", "neo4j", DatabaseType.GRAPH);
        Benchmark benchmark = mock(Benchmark.class);
        lenient().when(benchmark.getId()).thenReturn(BENCHMARK_ID);
        lenient().when(benchmark.getTopic()).thenReturn("Movies");
        lenient().when(benchmark.getDatabases()).thenReturn(List.of(pg, mongo, neo4j));
        lenient().when(benchmarkRepository.findById(BENCHMARK_ID)).thenReturn(Optional.of(benchmark));
    }

    @Test
    void throwsWhenBenchmarkMissing() {
        when(benchmarkRepository.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.build("nope"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void emitsPerDatabaseSummariesEvenWithoutRuns() {
        when(runRepository.findByBenchmarkIdOrderByCreatedAtDesc(BENCHMARK_ID)).thenReturn(List.of());

        ComparisonReportResponse report = service.build(BENCHMARK_ID);

        assertThat(report.databases()).extracting(ComparisonReportResponse.DatabaseDescriptor::dbName)
                .containsExactly("postgresql", "mongodb", "neo4j");
        assertThat(report.insertSummary()).hasSize(3).allSatisfy(s -> {
            assertThat(s.totalRuns()).isZero();
            assertThat(s.totalRowsInserted()).isZero();
            assertThat(s.avgDbTimeMs()).isNull();
        });
        assertThat(report.radarScores()).hasSize(3).allSatisfy(s -> {
            assertThat(s.insertSpeed()).isZero();
            assertThat(s.consistency()).isZero();
        });
    }

    @Test
    void insertSummaryAggregatesRowsConflictsAndAverages() {
        BenchmarkRun runA = insertRun(
                successInsert("db-pg", "postgresql", 1000L, 50_000_000L, 80_000_000L, 30_000_000L, 0, 1000L),
                successInsert("db-mongo", "mongodb", 1000L, 30_000_000L, 90_000_000L, 60_000_000L, 0, 1000L));
        BenchmarkRun runB = insertRun(
                successInsert("db-pg", "postgresql", 500L, 40_000_000L, 70_000_000L, 30_000_000L, 2, 500L),
                failedInsert("db-mongo", "mongodb", "boom"));

        when(runRepository.findByBenchmarkIdOrderByCreatedAtDesc(BENCHMARK_ID))
                .thenReturn(List.of(runA, runB));

        ComparisonReportResponse report = service.build(BENCHMARK_ID);
        ComparisonReportResponse.InsertSummary pgSummary = findByDb(report.insertSummary(), "db-pg");
        ComparisonReportResponse.InsertSummary mongoSummary = findByDb(report.insertSummary(), "db-mongo");

        assertThat(pgSummary.totalRuns()).isEqualTo(2);
        assertThat(pgSummary.totalRowsInserted()).isEqualTo(1500L);
        assertThat(pgSummary.totalConflicts()).isEqualTo(2L);
        assertThat(pgSummary.successCount()).isEqualTo(2);
        assertThat(pgSummary.failedCount()).isZero();
        assertThat(pgSummary.avgDbTimeMs()).isEqualTo(45.0);
        assertThat(pgSummary.avgWireTimeMs()).isEqualTo(75.0);

        assertThat(mongoSummary.totalRowsInserted()).isEqualTo(1000L);
        assertThat(mongoSummary.successCount()).isEqualTo(1);
        assertThat(mongoSummary.failedCount()).isEqualTo(1);
        assertThat(mongoSummary.avgDbTimeMs()).isEqualTo(30.0);
    }

    @Test
    void readSummaryAveragesPercentilesInMicroseconds() {
        BenchmarkRun run = readRun(
                successRead("db-pg", "postgresql", 100L, 100, 1_000_000L, 2_000_000L, 3_000_000L, 1_500_000L),
                successRead("db-pg", "postgresql", 100L, 100, 3_000_000L, 4_000_000L, 5_000_000L, 3_500_000L));

        when(runRepository.findByBenchmarkIdOrderByCreatedAtDesc(BENCHMARK_ID)).thenReturn(List.of(run));

        ComparisonReportResponse report = service.build(BENCHMARK_ID);
        ComparisonReportResponse.ReadSummary pgSummary = findByDb(report.readSummary(), "db-pg");

        assertThat(pgSummary.totalSamples()).isEqualTo(200L);
        assertThat(pgSummary.avgP50DbTimeUs()).isEqualTo(2000.0);
        assertThat(pgSummary.avgP95DbTimeUs()).isEqualTo(3000.0);
        assertThat(pgSummary.avgP99DbTimeUs()).isEqualTo(4000.0);
    }

    @Test
    void deleteSummaryComputesSizeFreedFromBeforeAfter() {
        BenchmarkRun run = deleteRun(
                successDelete("db-pg", "postgresql", 50L, 10_000_000L, 20_000_000L, 30_000_000L, 1_000_000L, 500_000L),
                successDelete("db-pg", "postgresql", 25L, 5_000_000L, 10_000_000L, 15_000_000L, 500_000L, 250_000L));

        when(runRepository.findByBenchmarkIdOrderByCreatedAtDesc(BENCHMARK_ID)).thenReturn(List.of(run));

        ComparisonReportResponse report = service.build(BENCHMARK_ID);
        ComparisonReportResponse.DeleteSummary pgSummary = findByDb(report.deleteSummary(), "db-pg");

        assertThat(pgSummary.totalRowsDeleted()).isEqualTo(75L);
        assertThat(pgSummary.totalSizeFreedBytes()).isEqualTo(750_000L);
    }

    @Test
    void radarRewardsLowerLatencyAndHigherThroughput() {
        BenchmarkRun ins = insertRun(
                successInsert("db-pg", "postgresql", 1000L, 50_000_000L, 60_000_000L, 10_000_000L, 0, 200L),
                successInsert("db-mongo", "mongodb", 1000L, 50_000_000L, 60_000_000L, 10_000_000L, 0, 100L));
        BenchmarkRun rd = readRun(
                successRead("db-pg", "postgresql", 100L, 100, 1_000_000L, 2_000_000L, 3_000_000L, 1_500_000L),
                successRead("db-mongo", "mongodb", 100L, 100, 4_000_000L, 8_000_000L, 12_000_000L, 6_000_000L));
        BenchmarkRun del = deleteRun(
                successDelete("db-pg", "postgresql", 50L, 5_000_000L, 10_000_000L, 15_000_000L, 1_000L, 0L),
                successDelete("db-mongo", "mongodb", 50L, 10_000_000L, 20_000_000L, 30_000_000L, 1_000L, 0L));

        when(runRepository.findByBenchmarkIdOrderByCreatedAtDesc(BENCHMARK_ID))
                .thenReturn(List.of(ins, rd, del));

        ComparisonReportResponse report = service.build(BENCHMARK_ID);
        ComparisonReportResponse.RadarScore pgRadar = findByDb(report.radarScores(), "db-pg");
        ComparisonReportResponse.RadarScore mongoRadar = findByDb(report.radarScores(), "db-mongo");

        assertThat(pgRadar.readSpeed()).isEqualTo(100.0);
        assertThat(mongoRadar.readSpeed()).isLessThan(pgRadar.readSpeed());

        assertThat(pgRadar.deleteSpeed()).isEqualTo(100.0);
        assertThat(mongoRadar.deleteSpeed()).isLessThan(pgRadar.deleteSpeed());

        assertThat(mongoRadar.insertSpeed()).isEqualTo(100.0);
        assertThat(pgRadar.insertSpeed()).isLessThan(mongoRadar.insertSpeed());

        assertThat(pgRadar.consistency()).isEqualTo(100.0);
        assertThat(mongoRadar.consistency()).isEqualTo(100.0);
    }

    @Test
    void radarHandlesAllNullsWithoutDivisionByZero() {
        BenchmarkRun run = readRun(
                pendingRead("db-pg", "postgresql"),
                pendingRead("db-mongo", "mongodb"));

        when(runRepository.findByBenchmarkIdOrderByCreatedAtDesc(BENCHMARK_ID)).thenReturn(List.of(run));

        ComparisonReportResponse report = service.build(BENCHMARK_ID);

        assertThat(report.radarScores()).allSatisfy(s -> {
            assertThat(s.insertSpeed()).isFinite().isZero();
            assertThat(s.readSpeed()).isFinite().isZero();
            assertThat(s.deleteSpeed()).isFinite().isZero();
            assertThat(s.sizeEfficiency()).isFinite().isZero();
        });
    }

    @Test
    void consistencyReflectsSuccessRatio() {
        BenchmarkRun run = insertRun(
                successInsert("db-pg", "postgresql", 100L, 1_000_000L, 2_000_000L, 1_000_000L, 0, 100L),
                failedInsert("db-pg", "postgresql", "fail"));

        when(runRepository.findByBenchmarkIdOrderByCreatedAtDesc(BENCHMARK_ID)).thenReturn(List.of(run));

        ComparisonReportResponse report = service.build(BENCHMARK_ID);
        ComparisonReportResponse.RadarScore pgRadar = findByDb(report.radarScores(), "db-pg");

        assertThat(pgRadar.consistency()).isEqualTo(50.0);
    }

    private BenchmarkDatabase mockDb(String id, String name, DatabaseType type) {
        BenchmarkDatabase db = mock(BenchmarkDatabase.class);
        lenient().when(db.getId()).thenReturn(id);
        lenient().when(db.getDbName()).thenReturn(name);
        lenient().when(db.getDbVersion()).thenReturn("16");
        lenient().when(db.getDbType()).thenReturn(type);
        return db;
    }

    private BenchmarkRun insertRun(BenchmarkResult... results) {
        return buildRun(OperationType.INSERT, results);
    }

    private BenchmarkRun readRun(BenchmarkResult... results) {
        return buildRun(OperationType.READ, results);
    }

    private BenchmarkRun deleteRun(BenchmarkResult... results) {
        return buildRun(OperationType.DELETE, results);
    }

    private BenchmarkRun buildRun(OperationType type, BenchmarkResult... results) {
        BenchmarkRun run = new BenchmarkRun(BENCHMARK_ID, type);
        for (BenchmarkResult r : results) run.addResult(r);
        return run;
    }

    private BenchmarkResult successInsert(String dbId, String dbName, long rows, long dbNs, long wireNs,
                                          long overheadNs, int conflicts, long durationMs) {
        BenchmarkResult r = new BenchmarkResult(dbId, dbName);
        r.setStatus(RunStatus.SUCCESS);
        r.setRowsAffected(rows);
        r.setDbTimeNs(dbNs);
        r.setWireTimeNs(wireNs);
        r.setOverheadNs(overheadNs);
        r.setConflictsSkipped(conflicts);
        r.setStartedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        r.setFinishedAt(java.time.Instant.parse("2026-01-01T00:00:00Z").plusMillis(durationMs));
        return r;
    }

    private BenchmarkResult failedInsert(String dbId, String dbName, String err) {
        BenchmarkResult r = new BenchmarkResult(dbId, dbName);
        r.setStatus(RunStatus.FAILED);
        r.setErrorMessage(err);
        return r;
    }

    private BenchmarkResult successRead(String dbId, String dbName, long rowsRead, int samples,
                                         long p50, long p95, long p99, long mean) {
        BenchmarkResult r = new BenchmarkResult(dbId, dbName);
        r.setStatus(RunStatus.SUCCESS);
        r.setRowsAffected(rowsRead);
        r.setSamplesRecorded(samples);
        r.setP50DbTimeNs(p50);
        r.setP95DbTimeNs(p95);
        r.setP99DbTimeNs(p99);
        r.setMeanDbTimeNs(mean);
        return r;
    }

    private BenchmarkResult pendingRead(String dbId, String dbName) {
        BenchmarkResult r = new BenchmarkResult(dbId, dbName);
        r.setStatus(RunStatus.PENDING);
        return r;
    }

    private BenchmarkResult successDelete(String dbId, String dbName, long rows, long p50, long p95, long p99,
                                          long sizeBefore, long sizeAfter) {
        BenchmarkResult r = new BenchmarkResult(dbId, dbName);
        r.setStatus(RunStatus.SUCCESS);
        r.setRowsAffected(rows);
        r.setP50DbTimeNs(p50);
        r.setP95DbTimeNs(p95);
        r.setP99DbTimeNs(p99);
        r.setMeanDbTimeNs(p50);
        r.setDataSizeBefore(sizeBefore);
        r.setDataSizeAfter(sizeAfter);
        return r;
    }

    private static <T> T findByDb(List<T> rows, String databaseId) {
        return rows.stream()
                .filter(r -> extractDbId(r).equals(databaseId))
                .findFirst()
                .orElseThrow();
    }

    private static String extractDbId(Object row) {
        return switch (row) {
            case ComparisonReportResponse.InsertSummary s -> s.databaseId();
            case ComparisonReportResponse.ReadSummary s -> s.databaseId();
            case ComparisonReportResponse.DeleteSummary s -> s.databaseId();
            case ComparisonReportResponse.RadarScore s -> s.databaseId();
            default -> throw new IllegalArgumentException("Unknown row type: " + row);
        };
    }
}
