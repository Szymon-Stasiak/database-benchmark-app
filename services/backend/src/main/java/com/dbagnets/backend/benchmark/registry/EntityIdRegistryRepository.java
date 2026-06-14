package com.dbagnets.backend.benchmark.registry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EntityIdRegistryRepository extends JpaRepository<EntityIdRecord, EntityIdRecord.RecordId> {

    long countByBenchmarkIdAndEntityName(String benchmarkId, String entityName);

    long countByDatabaseIdAndEntityName(String databaseId, String entityName);

    List<EntityIdRecord> findByDatabaseIdAndEntityName(String databaseId, String entityName);

    @Query("SELECT r.logicalId FROM EntityIdRecord r " +
            "WHERE r.benchmarkId = :benchmarkId AND r.entityName = :entityName " +
            "GROUP BY r.logicalId")
    List<String> distinctLogicalIds(@Param("benchmarkId") String benchmarkId,
                                     @Param("entityName") String entityName);

    @Modifying
    @Query("DELETE FROM EntityIdRecord r " +
            "WHERE r.databaseId = :databaseId AND r.entityName = :entityName " +
            "AND r.logicalId IN :logicalIds")
    int deleteByDatabaseIdAndEntityNameAndLogicalIdIn(@Param("databaseId") String databaseId,
                                                       @Param("entityName") String entityName,
                                                       @Param("logicalIds") List<String> logicalIds);

    @Query("SELECT r FROM EntityIdRecord r " +
            "WHERE r.databaseId = :databaseId AND r.entityName = :entityName " +
            "AND r.logicalId IN :logicalIds")
    List<EntityIdRecord> findByDatabaseAndEntityAndLogicalIds(@Param("databaseId") String databaseId,
                                                               @Param("entityName") String entityName,
                                                               @Param("logicalIds") List<String> logicalIds);

    @Modifying
    @Query("DELETE FROM EntityIdRecord r " +
            "WHERE r.databaseId = :databaseId AND r.entityName = :entityName " +
            "AND r.physicalId IN :physicalIds")
    int deleteByDatabaseIdAndEntityNameAndPhysicalIdIn(@Param("databaseId") String databaseId,
                                                        @Param("entityName") String entityName,
                                                        @Param("physicalIds") List<String> physicalIds);

    @Modifying
    @Query("DELETE FROM EntityIdRecord r WHERE r.databaseId = :databaseId")
    int deleteByDatabaseId(@Param("databaseId") String databaseId);

    @Modifying
    @Query("DELETE FROM EntityIdRecord r WHERE r.benchmarkId = :benchmarkId")
    int deleteByBenchmarkId(@Param("benchmarkId") String benchmarkId);
}
