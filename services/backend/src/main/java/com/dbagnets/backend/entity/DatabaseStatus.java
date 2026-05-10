package com.dbagnets.backend.entity;

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
