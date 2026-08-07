package com.dbagnets.backend.engine.registry;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "entity_id_registry",
        indexes = {
            @Index(name = "idx_registry_benchmark_entity", columnList = "benchmark_id,entity_name"),
            @Index(name = "idx_registry_database_entity", columnList = "database_id,entity_name")
        })
@IdClass(EntityIdRecord.RecordId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EntityIdRecord {

    @Id
    @Column(name = "database_id", nullable = false)
    private String databaseId;

    @Id
    @Column(name = "entity_name", nullable = false)
    private String entityName;

    @Id
    @Column(name = "logical_id", nullable = false)
    private String logicalId;

    @Column(name = "benchmark_id", nullable = false)
    private String benchmarkId;

    @Column(name = "physical_id", nullable = false, columnDefinition = "TEXT")
    private String physicalId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public EntityIdRecord(
            String benchmarkId,
            String databaseId,
            String entityName,
            String logicalId,
            String physicalId) {
        this.benchmarkId = benchmarkId;
        this.databaseId = databaseId;
        this.entityName = entityName;
        this.logicalId = logicalId;
        this.physicalId = physicalId;
        this.createdAt = Instant.now();
    }

    @NoArgsConstructor
    @Getter
    public static class RecordId implements Serializable {
        private String databaseId;
        private String entityName;
        private String logicalId;

        public RecordId(String databaseId, String entityName, String logicalId) {
            this.databaseId = databaseId;
            this.entityName = entityName;
            this.logicalId = logicalId;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RecordId other)) return false;
            return Objects.equals(databaseId, other.databaseId)
                    && Objects.equals(entityName, other.entityName)
                    && Objects.equals(logicalId, other.logicalId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(databaseId, entityName, logicalId);
        }
    }
}
