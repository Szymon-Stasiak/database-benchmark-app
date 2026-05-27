package com.dbagnets.backend.insert.entity;

import com.dbagnets.backend.entity.Benchmark;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "insert_runs")
public class InsertRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "benchmark_id", nullable = false)
    private Benchmark benchmark;

    /** The leaf entity the user picked (display label). For a cascade run this is the deepest
     *  child; the full ordered cascade is stored in {@link #cascadeJson}. */
    @Column(name = "entity_name", nullable = false)
    private String entityName;

    @Column(name = "record_count", nullable = false)
    private int recordCount;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Enumerated(EnumType.STRING)
    private InsertMode mode;

    @Column(name = "batch_size")
    private Integer batchSize;

    /** Per-DB Java Virtual Thread worker count from the request (one connection per worker). */
    @Column(name = "worker_count")
    private Integer workerCount;

    /** JSON-encoded {@code CascadePlan} snapshot — preserved on the run so the history view can
     *  reproduce per-entity counts and edges even after the schema mutates. */
    @Column(name = "cascade_json", columnDefinition = "TEXT")
    private String cascadeJson;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Enumerated(EnumType.STRING)
    private InsertStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<InsertResult> results = new ArrayList<>();

    protected InsertRun() {}

    public InsertRun(Benchmark benchmark, String entityName, int recordCount, InsertMode mode, Integer batchSize) {
        this(benchmark, entityName, recordCount, mode, batchSize, null, null);
    }

    public InsertRun(
        Benchmark benchmark,
        String entityName,
        int recordCount,
        InsertMode mode,
        Integer batchSize,
        Integer workerCount,
        String cascadeJson
    ) {
        this.benchmark = benchmark;
        this.entityName = entityName;
        this.recordCount = recordCount;
        this.mode = mode;
        this.batchSize = batchSize;
        this.workerCount = workerCount;
        this.cascadeJson = cascadeJson;
        this.status = InsertStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void addResult(InsertResult result) {
        results.add(result);
        result.setRun(this);
    }

    public String getId() { return id; }
    public Benchmark getBenchmark() { return benchmark; }
    public String getEntityName() { return entityName; }
    public int getRecordCount() { return recordCount; }
    public InsertMode getMode() { return mode; }
    public Integer getBatchSize() { return batchSize; }
    public Integer getWorkerCount() { return workerCount; }
    public String getCascadeJson() { return cascadeJson; }
    public InsertStatus getStatus() { return status; }
    public void setStatus(InsertStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public List<InsertResult> getResults() { return results; }
}
