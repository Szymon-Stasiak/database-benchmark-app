package com.dbagnets.backend.docker.strategy;

import com.dbagnets.backend.docker.DockerService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PostgresScriptStrategy implements ScriptExecutionStrategy {

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
        String result = docker.execWithStdin(containerId, script, "psql", "-U", "postgres",
            "-d", "benchmark", "-v", "ON_ERROR_STOP=1", "-q");
        if (result != null && result.toLowerCase().contains("error")) {
            String firstError = result.lines()
                .filter(l -> l.toLowerCase().contains("error"))
                .findFirst()
                .orElse(result);
            throw new RuntimeException("PostgreSQL init script failed: " + firstError);
        }
        log.info("PostgreSQL script executed cleanly ({} chars output)",
            result == null ? 0 : result.length());
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
