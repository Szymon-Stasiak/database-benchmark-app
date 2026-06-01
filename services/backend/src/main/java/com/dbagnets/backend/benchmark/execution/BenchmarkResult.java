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
