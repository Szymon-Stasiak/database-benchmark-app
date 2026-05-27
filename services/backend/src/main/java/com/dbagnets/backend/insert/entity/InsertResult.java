package com.dbagnets.backend.insert.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One result row = one (database × entity) phase of a cascade insert run.
 *
 * <p>For a single-entity run there is one row per database (legacy behaviour: {@code entityName}
 * may be null on rows produced before the cascade migration). For a multi-entity cascade there
 * are {@code #entities × #databases} rows.
 */
@Entity
@Table(name = "insert_results")
public class InsertResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private InsertRun run;

    @Column(name = "database_id", nullable = false)
    private String databaseId;

    @Column(name = "db_name", nullable = false)
    private String dbName;

    /** Entity name this row covers (null on legacy single-entity rows). */
    @Column(name = "entity_name")
    private String entityName;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Enumerated(EnumType.STRING)
    private InsertStatus status;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "records_inserted")
    private Integer recordsInserted;

    @Column(name = "throughput_rps")
    private Double throughputRps;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    protected InsertResult() {}

    public InsertResult(String databaseId, String dbName) {
        this(databaseId, dbName, null);
    }

    public InsertResult(String databaseId, String dbName, String entityName) {
        this.databaseId = databaseId;
        this.dbName = dbName;
        this.entityName = entityName;
        this.status = InsertStatus.PENDING;
    }

    public String getId() { return id; }
    public InsertRun getRun() { return run; }
    void setRun(InsertRun run) { this.run = run; }
    public String getDatabaseId() { return databaseId; }
    public String getDbName() { return dbName; }
    public String getEntityName() { return entityName; }
    public void setEntityName(String entityName) { this.entityName = entityName; }
    public InsertStatus getStatus() { return status; }
    public void setStatus(InsertStatus status) { this.status = status; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Integer getRecordsInserted() { return recordsInserted; }
    public void setRecordsInserted(Integer recordsInserted) { this.recordsInserted = recordsInserted; }
    public Double getThroughputRps() { return throughputRps; }
    public void setThroughputRps(Double throughputRps) { this.throughputRps = throughputRps; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
