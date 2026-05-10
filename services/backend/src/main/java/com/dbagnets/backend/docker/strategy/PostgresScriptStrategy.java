package com.dbagnets.backend.docker.strategy;

import com.dbagnets.backend.docker.DockerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PostgresScriptStrategy implements ScriptExecutionStrategy {
    private static final Logger log = LoggerFactory.getLogger(PostgresScriptStrategy.class);

    @Override
    public void waitForReady(DockerService docker, String containerId, int hostPort) {
        for (int i = 0; i < 30; i++) {
            String result = docker.execInContainer(containerId, "pg_isready", "-U", "postgres");
            if (result.contains("accepting connections")) {
                log.info("PostgreSQL is ready");
                return;
            }
            sleep(2000);
        }
        throw new RuntimeException("PostgreSQL did not become ready in time");
    }

    @Override
    public void execute(DockerService docker, String containerId, String script, int hostPort) {
        String result = docker.execWithStdin(containerId, script, "psql", "-U", "postgres");
        log.info("PostgreSQL script executed: {}", result.substring(0, Math.min(200, result.length())));
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
