package com.dbagnets.backend.insert.repository;

import com.dbagnets.backend.insert.entity.InsertResult;
import com.dbagnets.backend.insert.entity.InsertStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;

public interface InsertResultRepository extends JpaRepository<InsertResult, String> {
    /** True once any insert phase has been queued for this database. */
    boolean existsByDatabaseId(String databaseId);

    /** True once an insert phase has moved past PENDING — i.e. a strategy actually started
     *  writing to this DB. Used to drive two things:
     *  <ol>
     *    <li>{@code DatabaseSizeService}'s pre-insert clamp: stays ON while everything is PENDING,
     *        flips OFF as soon as a real write starts.</li>
     *    <li>{@code BaselineSizeService.captureForFirstInsertOnly}: only re-freezes the baseline
     *        the first time a strategy is about to run — subsequent runs leave it locked so the
     *        cyan engine bar doesn't absorb data from previous runs.</li>
     *  </ol> */
    boolean existsByDatabaseIdAndStatusIn(String databaseId, Collection<InsertStatus> statuses);
}
