package com.dbagnets.backend.repository;

import com.dbagnets.backend.entity.Benchmark;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BenchmarkRepository extends JpaRepository<Benchmark, String> {
    List<Benchmark> findByUserEmailOrderByCreatedAtDesc(String userEmail);
}
