package com.dbagnets.backend.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "benchmarks")
public class Benchmark {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String topic;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Enumerated(EnumType.STRING)
    private BenchmarkStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "user_email", nullable = false)
    private String userEmail;

    @Column(name = "logical_schema", columnDefinition = "TEXT")
    private String logicalSchema;

    @OneToMany(mappedBy = "benchmark", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<BenchmarkDatabase> databases = new ArrayList<>();

    protected Benchmark() {}

    public Benchmark(String topic, String userEmail) {
        this.topic = topic;
        this.userEmail = userEmail;
        this.status = BenchmarkStatus.PENDING;
        this.createdAt = Instant.now();
    }

    // Getters and setters
    public String getId() { return id; }
    public String getTopic() { return topic; }
    public BenchmarkStatus getStatus() { return status; }
    public void setStatus(BenchmarkStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public String getUserEmail() { return userEmail; }
    public String getLogicalSchema() { return logicalSchema; }
    public void setLogicalSchema(String logicalSchema) { this.logicalSchema = logicalSchema; }
    public List<BenchmarkDatabase> getDatabases() { return databases; }

    public void addDatabase(BenchmarkDatabase db) {
        databases.add(db);
        db.setBenchmark(this);
    }
}
