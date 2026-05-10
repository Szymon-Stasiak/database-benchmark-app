package com.dbagnets.backend.entity;

public enum BenchmarkStatus {
    PENDING,
    GENERATING_SCRIPTS,
    READY_TO_RUN,
    STARTING_CONTAINERS,
    INITIALIZING,
    RUNNING,
    STOPPED,
    FAILED
}
