package com.dbagnets.backend.benchmark.run.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BenchmarkRunRepository extends JpaRepository<BenchmarkRun, String> {
    List<BenchmarkRun> findByBenchmarkIdAndOperationTypeOrderByCreatedAtDesc(String benchmarkId, OperationType operationType);

    List<BenchmarkRun> findByBenchmarkIdOrderByCreatedAtDesc(String benchmarkId);
}
