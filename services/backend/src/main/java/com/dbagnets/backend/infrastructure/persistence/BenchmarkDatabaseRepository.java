package com.dbagnets.backend.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dbagnets.backend.domain.DatabaseStatus;
import com.dbagnets.backend.shared.entity.BenchmarkDatabase;
import com.dbagnets.backend.shared.entity.User;

public interface BenchmarkDatabaseRepository extends JpaRepository<BenchmarkDatabase, String> {
    long countByBenchmark_UserAndStatus(User user, DatabaseStatus status);
}
