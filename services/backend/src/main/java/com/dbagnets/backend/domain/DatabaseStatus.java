package com.dbagnets.backend.domain;

public enum DatabaseStatus {
    PENDING,
    SCRIPT_GENERATING,
    SCRIPT_READY,
    CONTAINER_STARTING,
    INITIALIZING,
    RUNNING,
    STOPPED,
    FAILED
}