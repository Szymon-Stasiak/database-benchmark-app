package com.dbagnets.backend.benchmark.setup.port;

public interface ScriptExecutionPort {

    void waitForReady(String containerId, String dbName, int hostPort);

    void executeScript(String containerId, String dbName, String script, int hostPort);
}