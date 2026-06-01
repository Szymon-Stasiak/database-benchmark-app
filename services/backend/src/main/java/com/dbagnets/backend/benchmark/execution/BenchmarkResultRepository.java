package com.dbagnets.backend.benchmark.execution;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BenchmarkResultRepository extends JpaRepository<BenchmarkResult, String> {
}
