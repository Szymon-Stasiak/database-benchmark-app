package com.dbagnets.backend.infrastructure.persistence;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.dbagnets.backend.shared.entity.Benchmark;
import com.dbagnets.backend.shared.entity.User;

public interface BenchmarkRepository extends JpaRepository<Benchmark, String> {
    List<Benchmark> findByUserOrderByCreatedAtDesc(User user);

    List<Benchmark> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);

    long countByUser(User user);
}
