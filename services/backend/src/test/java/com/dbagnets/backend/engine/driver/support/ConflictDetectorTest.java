package com.dbagnets.backend.engine.driver.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import com.dbagnets.backend.domain.DatabaseEngine;

class ConflictDetectorTest {

    @Test
    void nullErrorIsNotConflict() {
        assertThat(ConflictDetector.isConflict(DatabaseEngine.POSTGRESQL, null)).isFalse();
    }

    @Test
    void postgresqlDetectsSqlStateOnConflict() {
        SQLException conflict = new SQLException("duplicate", "23505");
        assertThat(ConflictDetector.isConflict(DatabaseEngine.POSTGRESQL, conflict)).isTrue();
    }

    @Test
    void postgresqlDetectsInNestedCause() {
        SQLException nested = new SQLException("nested", "23505");
        RuntimeException wrapper = new RuntimeException("outer", nested);
        assertThat(ConflictDetector.isConflict(DatabaseEngine.POSTGRESQL, wrapper)).isTrue();
    }

    @Test
    void postgresqlDetectsByMessageWhenSqlStateMissing() {
        RuntimeException ex =
                new RuntimeException("duplicate key value violates unique constraint");
        assertThat(ConflictDetector.isConflict(DatabaseEngine.POSTGRESQL, ex)).isTrue();
    }

    @Test
    void postgresqlUnrelatedErrorNotConflict() {
        assertThat(
                        ConflictDetector.isConflict(
                                DatabaseEngine.POSTGRESQL, new RuntimeException("connection lost")))
                .isFalse();
    }

    @Test
    void mysqlDetectsErrorCode1062() {
        SQLException conflict = new SQLException("duplicate", "23000", 1062);
        assertThat(ConflictDetector.isConflict(DatabaseEngine.MYSQL, conflict)).isTrue();
    }

    @Test
    void mysqlDetectsByMessage() {
        assertThat(
                        ConflictDetector.isConflict(
                                DatabaseEngine.MYSQL, new RuntimeException("Duplicate entry '1'")))
                .isTrue();
    }

    @Test
    void mongoDbDetectsE11000() {
        assertThat(
                        ConflictDetector.isConflict(
                                DatabaseEngine.MONGODB,
                                new RuntimeException("E11000 duplicate key error")))
                .isTrue();
    }

    @Test
    void neo4jDetectsConstraintViolation() {
        assertThat(
                        ConflictDetector.isConflict(
                                DatabaseEngine.NEO4J,
                                new RuntimeException("ConstraintValidationFailed")))
                .isTrue();
    }

    @Test
    void elasticsearchDetectsVersionConflict() {
        assertThat(
                        ConflictDetector.isConflict(
                                DatabaseEngine.ELASTICSEARCH,
                                new RuntimeException("version_conflict")))
                .isTrue();
    }
}
