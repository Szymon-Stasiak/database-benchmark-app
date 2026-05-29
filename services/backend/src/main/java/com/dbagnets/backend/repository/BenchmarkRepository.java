package com.dbagnets.backend.repository;

import com.dbagnets.backend.entity.Benchmark;
import com.dbagnets.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BenchmarkRepository extends JpaRepository<Benchmark, String> {
    List<Benchmark> findByUserOrderByCreatedAtDesc(User user);
}
