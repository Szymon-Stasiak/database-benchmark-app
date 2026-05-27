package com.dbagnets.backend.insert.repository;

import com.dbagnets.backend.insert.entity.InsertRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InsertRunRepository extends JpaRepository<InsertRun, String> {
    List<InsertRun> findByBenchmarkIdOrderByCreatedAtDesc(String benchmarkId);
    /** Used by hard reset to wipe insert history so the size chart's "no inserts yet → clamp
     *  dataBytes to 0" rule kicks back in for the redeployed containers. Cascade on
     *  {@link InsertRun#getResults()} drops the {@code InsertResult} rows too. */
    long deleteByBenchmarkId(String benchmarkId);
}
