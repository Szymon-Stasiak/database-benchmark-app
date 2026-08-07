package com.dbagnets.backend.shared.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.*;

import com.dbagnets.backend.domain.BenchmarkStatus;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "benchmarks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Benchmark {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String topic;

    @Setter
    @Column(nullable = false, columnDefinition = "TEXT")
    @Enumerated(EnumType.STRING)
    private BenchmarkStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private int depth;

    @Setter
    @Column(name = "logical_schema", columnDefinition = "TEXT")
    private String logicalSchema;

    @OneToMany(
            mappedBy = "benchmark",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.EAGER)
    private List<BenchmarkDatabase> databases = new ArrayList<>();

    @Version
    @Column(nullable = false)
    private Long version;

    public Benchmark(String topic, User user, int depth) {
        this.topic = topic;
        this.user = user;
        this.depth = depth;
        this.status = BenchmarkStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void addDatabase(BenchmarkDatabase db) {
        databases.add(db);
        db.setBenchmark(this);
    }
}
