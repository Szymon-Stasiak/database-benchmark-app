package com.dbagnets.backend.benchmark.run.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "benchmark_runs", indexes = {
        @Index(name = "idx_runs_benchmark_type", columnList = "benchmark_id, operation_type")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BenchmarkRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "benchmark_id", nullable = false)
    private String benchmarkId;

    @Setter
    @Column(name = "operation_type", nullable = false, columnDefinition = "TEXT")
    @Enumerated(EnumType.STRING)
    private OperationType operationType;

    @Setter
    @Column(name = "entity_name")
    private String entityName;

    @Setter
    @Column(name = "record_count")
    private Long recordCount;

    @Setter
    @Column(name = "mode")
    private String mode;

    @Setter
    @Column(name = "batch_size")
    private Integer batchSize;

    @Setter
    @Column(name = "worker_count")
    private Integer workerCount;

    @Setter
    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @Setter
    @Column(name = "cascade_json", columnDefinition = "TEXT")
    private String cascadeJson;

    @Setter
    @Column(name = "selected_ids_json", columnDefinition = "TEXT")
    private String selectedIdsJson;

    @Setter
    @Column(name = "cascade_preview_json", columnDefinition = "TEXT")
    private String cascadePreviewJson;

    @Setter
    @Column(name = "scenario_type", columnDefinition = "TEXT")
    private String scenarioType;

    @Setter
    @Column(name = "scenario_consistency_status", columnDefinition = "TEXT")
    private String scenarioConsistencyStatus;

    @Setter
    @Column(name = "iterations")
    private Integer iterations;

    @Setter
    @Column(nullable = false, columnDefinition = "TEXT")
    @Enumerated(EnumType.STRING)
    private RunStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "finished_at")
    private Instant finishedAt;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<BenchmarkResult> results = new ArrayList<>();

    public BenchmarkRun(String benchmarkId, OperationType operationType) {
        this.benchmarkId = benchmarkId;
        this.operationType = operationType;
        this.status = RunStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void addResult(BenchmarkResult result) {
        results.add(result);
        result.setRun(this);
    }
}