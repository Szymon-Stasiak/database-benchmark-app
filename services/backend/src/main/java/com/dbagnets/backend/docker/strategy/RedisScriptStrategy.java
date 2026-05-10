package com.dbagnets.backend.docker.strategy;

import com.dbagnets.backend.docker.DockerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedisScriptStrategy implements ScriptExecutionStrategy {
    private static final Logger log = LoggerFactory.getLogger(RedisScriptStrategy.class);

    @Override
    public void waitForReady(DockerService docker, String containerId, int hostPort) {
        for (int i = 0; i < 30; i++) {
            String result = docker.execInContainer(containerId, "redis-cli", "PING");
            if (result.contains("PONG")) { log.info("Redis is ready"); return; }
            sleep(2000);
        }
        throw new RuntimeException("Redis did not become ready in time");
    }

    @Override
    public void execute(DockerService docker, String containerId, String script, int hostPort) {
        String result = docker.execWithStdin(containerId, script, "redis-cli");
        log.info("Redis script executed: {}", result.substring(0, Math.min(200, result.length())));
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
