package com.dbagnets.backend.insert.strategy;

public record InsertOutcome(
    boolean success,
    int recordsInserted,
    long durationMs,
    String errorMessage
) {
    public static InsertOutcome success(int recordsInserted, long durationMs) {
        return new InsertOutcome(true, recordsInserted, durationMs, null);
    }

    public static InsertOutcome failure(String errorMessage, long durationMs) {
        return new InsertOutcome(false, 0, durationMs, errorMessage);
    }
}
