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
        String result = docker.execWithStdin(containerId, script,
            "mongosh", "benchmark", "--quiet");

        if (result != null && containsError(result)) {
            String firstError = result.lines()
                .filter(this::isErrorLine)
                .findFirst()
                .orElse(result);
            throw new RuntimeException("MongoDB init script failed: " + firstError);
        }
        log.info("MongoDB script executed cleanly ({} chars output)",
            result == null ? 0 : result.length());
    }

    private boolean containsError(String output) {
        return output.lines().anyMatch(this::isErrorLine);
    }

    private boolean isErrorLine(String line) {
        String trimmed = line.stripLeading();
        return trimmed.startsWith("MongoServerError")
            || trimmed.startsWith("MongoBulkWriteError")
            || trimmed.startsWith("BulkWriteError")
            || trimmed.startsWith("SyntaxError")
            || trimmed.startsWith("TypeError")
            || trimmed.startsWith("ReferenceError")
            || trimmed.startsWith("MongoshInvalidInputError")
            || trimmed.startsWith("Uncaught");
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
