package com.dbagnets.backend.engine.driver;

import com.dbagnets.backend.domain.DatabaseEngine;

import java.sql.SQLException;
import java.util.Locale;

public final class ConflictDetector {

    private ConflictDetector() {
    }

    public static boolean isConflict(DatabaseEngine engine, Throwable error) {
        if (error == null) {
            return false;
        }
        return switch (engine) {
            case POSTGRESQL, TIMESCALEDB -> matchesPgConflict(error);
            case MYSQL -> matchesMySqlConflict(error);
            case MONGODB -> matchesMessage(error, "e11000", "duplicate key");
            case NEO4J, MEMGRAPH -> matchesMessage(error, "constraintvalidationfailed", "already exists");
            case QDRANT, ELASTICSEARCH, COUCHDB, ARANGODB ->
                    matchesMessage(error, "already exists", "version_conflict");
            default -> false;
        };
    }

    private static boolean matchesPgConflict(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return matchesMessage(error, "duplicate key value", "unique constraint");
    }

    private static boolean matchesMySqlConflict(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof SQLException sql && sql.getErrorCode() == 1062) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return matchesMessage(error, "duplicate entry");
    }

    private static boolean matchesMessage(Throwable error, String... tokens) {
        Throwable cursor = error;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                for (String token : tokens) {
                    if (lower.contains(token)) {
                        return true;
                    }
                }
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
