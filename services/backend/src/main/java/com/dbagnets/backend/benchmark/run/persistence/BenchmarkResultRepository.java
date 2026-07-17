package com.dbagnets.backend.benchmark.run.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BenchmarkResultRepository extends JpaRepository<BenchmarkResult, String> {
}
