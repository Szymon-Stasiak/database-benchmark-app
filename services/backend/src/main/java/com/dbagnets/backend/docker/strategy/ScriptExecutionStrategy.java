package com.dbagnets.backend.docker.strategy;

import com.dbagnets.backend.docker.DockerService;

public interface ScriptExecutionStrategy {
    void execute(DockerService docker, String containerId, String script, int hostPort);
    void waitForReady(DockerService docker, String containerId, int hostPort);
}
