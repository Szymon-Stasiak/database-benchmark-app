package com.dbagnets.backend.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "benchmark_databases")
public class BenchmarkDatabase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

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

    @Column(nullable = false, columnDefinition = "TEXT")
    @Enumerated(EnumType.STRING)
    private DatabaseStatus status;

    @Column(name = "container_id")
    private String containerId;

    @Column(columnDefinition = "TEXT")
    private String script;

    @Column(name = "host_port")
    private Integer hostPort;

    @Column(name = "docker_image")
    private String dockerImage;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    protected BenchmarkDatabase() {}

    public BenchmarkDatabase(DatabaseType dbType, String dbName, String dbVersion) {
        this.dbType = dbType;
        this.dbName = dbName;
        this.dbVersion = dbVersion;
        this.status = DatabaseStatus.PENDING;
    }

    // Getters and setters
    public String getId() { return id; }
    public Benchmark getBenchmark() { return benchmark; }
    void setBenchmark(Benchmark benchmark) { this.benchmark = benchmark; }
    public DatabaseType getDbType() { return dbType; }
    public String getDbName() { return dbName; }
    public String getDbVersion() { return dbVersion; }
    public DatabaseStatus getStatus() { return status; }
    public void setStatus(DatabaseStatus status) { this.status = status; }
    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }
    public String getScript() { return script; }
    public void setScript(String script) { this.script = script; }
    public Integer getHostPort() { return hostPort; }
    public void setHostPort(Integer hostPort) { this.hostPort = hostPort; }
    public String getDockerImage() { return dockerImage; }
    public void setDockerImage(String dockerImage) { this.dockerImage = dockerImage; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
