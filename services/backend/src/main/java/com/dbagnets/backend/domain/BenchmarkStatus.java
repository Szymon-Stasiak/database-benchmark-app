package com.dbagnets.backend.domain;

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