package com.dbagnets.backend.docker.strategy;

import com.dbagnets.backend.docker.DockerService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MysqlScriptStrategy implements ScriptExecutionStrategy {

    @Override
    public void waitForReady(DockerService docker, String containerId, int hostPort) {
        for (int i = 0; i < 30; i++) {
            String result = docker.execInContainer(containerId, "mysqladmin", "ping", "-u", "root", "--password=root", "--silent");
            if (result.contains("alive")) {
                log.info("MySQL is ready");
                return;
            }
            sleep(2000);
        }
        throw new RuntimeException("MySQL did not become ready in time");
    }

    @Override
    public void execute(DockerService docker, String containerId, String script, int hostPort) {
        String result = docker.execWithStdin(containerId, script,
            "mysql", "-u", "root", "--password=root",
            "--database=benchmark",
            "--abort-source-on-error");

        if (result != null && containsError(result)) {
            String firstError = result.lines()
                .filter(this::isErrorLine)
                .findFirst()
                .orElse(result);
            throw new RuntimeException("MySQL init script failed: " + firstError);
        }
        log.info("MySQL script executed cleanly ({} chars output)",
            result == null ? 0 : result.length());
    }

    private boolean containsError(String output) {
        return output.lines().anyMatch(this::isErrorLine);
    }

    private boolean isErrorLine(String line) {
        String trimmed = line.stripLeading();
        return trimmed.startsWith("ERROR ") || trimmed.startsWith("ERROR\t");
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
