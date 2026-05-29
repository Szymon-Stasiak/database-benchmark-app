package com.dbagnets.backend.insert.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InsertResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Setter(AccessLevel.PACKAGE)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private InsertRun run;

    @Column(name = "database_id", nullable = false)
    private String databaseId;

    @Column(name = "db_name", nullable = false)
    private String dbName;

    /** Entity name this row covers (null on legacy single-entity rows). */
    @Setter
    @Column(name = "entity_name")
    private String entityName;

    @Setter
    @Column(nullable = false, columnDefinition = "TEXT")
    @Enumerated(EnumType.STRING)
    private InsertStatus status;

    @Setter
    @Column(name = "started_at")
    private Instant startedAt;

    @Setter
    @Column(name = "finished_at")
    private Instant finishedAt;

    @Setter
    @Column(name = "duration_ms")
    private Long durationMs;

    @Setter
    @Column(name = "records_inserted")
    private Integer recordsInserted;

    @Setter
    @Column(name = "throughput_rps")
    private Double throughputRps;

    @Setter
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    public InsertResult(String databaseId, String dbName) {
        this(databaseId, dbName, null);
    }

    public InsertResult(String databaseId, String dbName, String entityName) {
        this.databaseId = databaseId;
        this.dbName = dbName;
        this.entityName = entityName;
        this.status = InsertStatus.PENDING;
    }
}
