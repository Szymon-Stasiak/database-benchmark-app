package com.dbagnets.backend.benchmark.run.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BenchmarkRunRepository extends JpaRepository<BenchmarkRun, String> {
    List<BenchmarkRun> findByBenchmarkIdAndOperationTypeOrderByCreatedAtDesc(
            String benchmarkId, OperationType operationType);

    List<BenchmarkRun> findByBenchmarkIdOrderByCreatedAtDesc(String benchmarkId);
}
