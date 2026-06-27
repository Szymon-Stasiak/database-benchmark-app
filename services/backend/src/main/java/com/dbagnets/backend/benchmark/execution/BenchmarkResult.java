package com.dbagnets.backend.benchmark.execution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "benchmark_results", indexes = {
        @Index(name = "idx_results_run", columnList = "run_id"),
        @Index(name = "idx_results_database", columnList = "database_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BenchmarkResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Setter(AccessLevel.PACKAGE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    private BenchmarkRun run;

    @Column(name = "database_id", nullable = false)
    private String databaseId;

    @Column(name = "db_name", nullable = false)
    private String dbName;

    @Setter
    @Column(name = "entity_name")
    private String entityName;

    @Setter
    @Column(nullable = false, columnDefinition = "TEXT")
    @Enumerated(EnumType.STRING)
    private RunStatus status;

    @Setter
    @Column(name = "db_time_ns")
    private Long dbTimeNs;

    @Setter
    @Column(name = "wire_time_ns")
    private Long wireTimeNs;

    @Setter
    @Column(name = "overhead_ns")
    private Long overheadNs;

    @Setter
    @Column(name = "rows_affected")
    private Long rowsAffected;

    @Setter
    @Column(name = "cascade_rows_affected")
    private Long cascadeRowsAffected;

    @Setter
    @Column(name = "cascade_breakdown_json", columnDefinition = "TEXT")
    private String cascadeBreakdownJson;

    @Setter
    @Column(name = "data_size_before")
    private Long dataSizeBefore;

    @Setter
    @Column(name = "data_size_after")
    private Long dataSizeAfter;

    @Setter
    @Column(name = "conflicts_skipped")
    private Integer conflictsSkipped;

    @Setter
    @Column(name = "p50_db_time_ns")
    private Long p50DbTimeNs;

    @Setter
    @Column(name = "p95_db_time_ns")
    private Long p95DbTimeNs;

    @Setter
    @Column(name = "p99_db_time_ns")
    private Long p99DbTimeNs;

    @Setter
    @Column(name = "mean_db_time_ns")
    private Long meanDbTimeNs;

    @Setter
    @Column(name = "samples_recorded")
    private Integer samplesRecorded;

    @Setter
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Setter
    @Column(name = "started_at")
    private Instant startedAt;

    @Setter
    @Column(name = "finished_at")
    private Instant finishedAt;

    @Setter
    @Column(name = "cpu_percent_max")
    private Double cpuPercentMax;

    @Setter
    @Column(name = "cpu_percent_mean")
    private Double cpuPercentMean;

    @Setter
    @Column(name = "cpu_percent_p95")
    private Double cpuPercentP95;

    @Setter
    @Column(name = "memory_bytes_max")
    private Long memoryBytesMax;

    @Setter
    @Column(name = "memory_bytes_mean")
    private Long memoryBytesMean;

    @Setter
    @Column(name = "memory_bytes_p95")
    private Long memoryBytesP95;

    @Setter
    @Column(name = "resource_sample_count")
    private Integer resourceSampleCount;

    @Setter
    @Column(name = "resource_samples_json", columnDefinition = "TEXT")
    private String resourceSamplesJson;

    @Setter
    @Column(name = "scenario_type", columnDefinition = "TEXT")
    private String scenarioType;

    @Setter
    @Column(name = "scenario_result_json", columnDefinition = "TEXT")
    private String scenarioResultJson;

    @Setter
    @Column(name = "scenario_result_hash", columnDefinition = "TEXT")
    private String scenarioResultHash;

    @Setter
    @Column(name = "scenario_rows_returned")
    private Long scenarioRowsReturned;

    public BenchmarkResult(String databaseId, String dbName) {
        this.databaseId = databaseId;
        this.dbName = dbName;
        this.status = RunStatus.PENDING;
        this.conflictsSkipped = 0;
    }

    public Long durationMs() {
        if (startedAt == null || finishedAt == null) {
            return null;
        }
        return finishedAt.toEpochMilli() - startedAt.toEpochMilli();
    }
}
