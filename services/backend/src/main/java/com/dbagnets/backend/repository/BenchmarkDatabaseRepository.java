package com.dbagnets.backend.repository;

import com.dbagnets.backend.entity.BenchmarkDatabase;
import com.dbagnets.backend.entity.DatabaseStatus;
import com.dbagnets.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BenchmarkDatabaseRepository extends JpaRepository<BenchmarkDatabase, String> {
    List<BenchmarkDatabase> findByStatusIn(List<DatabaseStatus> statuses);
    long countByBenchmark_UserAndStatus(User user, DatabaseStatus status);
}
