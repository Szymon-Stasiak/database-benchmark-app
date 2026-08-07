package com.dbagnets.backend.shared.entity;

import java.time.Instant;

import jakarta.persistence.*;

import com.dbagnets.backend.domain.DatabaseStatus;
import com.dbagnets.backend.domain.DatabaseType;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "benchmark_databases")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BenchmarkDatabase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Setter(AccessLevel.PACKAGE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "benchmark_id", nullable = false)
    private Benchmark benchmark;

    @Column(name = "db_type", nullable = false, columnDefinition = "TEXT")
    @Enumerated(EnumType.STRING)
    private DatabaseType dbType;

    @Column(name = "db_name", nullable = false)
    private String dbName;

    @Column(name = "db_version", nullable = false)
    private String dbVersion;

    @Setter
    @Column(nullable = false, columnDefinition = "TEXT")
    @Enumerated(EnumType.STRING)
    private DatabaseStatus status;

    @Setter
    @Column(name = "container_id")
    private String containerId;

    @Setter
    @Column(columnDefinition = "TEXT")
    private String script;

    @Setter
    @Column(name = "embedding_mappings", columnDefinition = "TEXT")
    private String embeddingMappings;

    @Setter
    @Column(name = "host_port")
    private Integer hostPort;

    @Setter
    @Column(name = "docker_image")
    private String dockerImage;

    @Setter
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Setter
    @Column(name = "baseline_size_bytes")
    private Long baselineSizeBytes;

    @Setter
    @Column(name = "baseline_recorded_at")
    private Instant baselineRecordedAt;

    public BenchmarkDatabase(DatabaseType dbType, String dbName, String dbVersion) {
        this.dbType = dbType;
        this.dbName = dbName;
        this.dbVersion = dbVersion;
        this.status = DatabaseStatus.PENDING;
    }
}
