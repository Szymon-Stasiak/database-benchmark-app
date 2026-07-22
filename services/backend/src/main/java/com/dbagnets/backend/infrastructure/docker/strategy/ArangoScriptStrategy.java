package com.dbagnets.backend.infrastructure.docker.strategy;

import com.dbagnets.backend.infrastructure.docker.DockerService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ArangoScriptStrategy implements ScriptExecutionStrategy {

    @Override
    public void waitForReady(DockerService docker, String containerId, int hostPort) {
        for (int i = 0; i < 30; i++) {
            String result = docker.execInContainer(containerId, "arangosh",
                "--server.password", "root",
                "--javascript.execute-string", "db._version()");
            if (result != null && !result.isEmpty() && !result.contains("Error")) {
                log.info("ArangoDB is ready");
                return;
            }
            sleep(2000);
        }
        throw new RuntimeException("ArangoDB did not become ready in time");
    }

    @Override
    public void execute(DockerService docker, String containerId, String script, int hostPort) {
        String result = docker.execWithStdin(containerId, script, "arangosh",
            "--server.password", "root",
            "--javascript.execute");
        log.info("ArangoDB script executed: {}", result.substring(0, Math.min(200, result.length())));
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}