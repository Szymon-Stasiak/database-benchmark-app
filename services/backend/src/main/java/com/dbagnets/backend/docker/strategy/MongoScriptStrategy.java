package com.dbagnets.backend.docker.strategy;

import com.dbagnets.backend.docker.DockerService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MongoScriptStrategy implements ScriptExecutionStrategy {

    @Override
    public void waitForReady(DockerService docker, String containerId, int hostPort) {
        for (int i = 0; i < 30; i++) {
            String result = docker.execInContainer(containerId, "mongosh", "--eval", "db.runCommand({ping:1})");
            if (result.contains("ok")) { log.info("MongoDB is ready"); return; }
            sleep(2000);
        }
        throw new RuntimeException("MongoDB did not become ready in time");
    }

    @Override
    public void execute(DockerService docker, String containerId, String script, int hostPort) {
        String result = docker.execWithStdin(containerId, script, "mongosh", "benchmark");
        log.info("MongoDB script executed: {}", result.substring(0, Math.min(200, result.length())));
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
