package com.dbagnets.backend.docker.strategy;

import com.dbagnets.backend.docker.DockerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Slf4j
public class CypherScriptStrategy implements ScriptExecutionStrategy {
    private final String dbName;

    @Override
    public void waitForReady(DockerService docker, String containerId, int hostPort) {
        for (int i = 0; i < 60; i++) {
            try {
                if ("memgraph".equals(dbName)) {
                    String result = docker.execInContainer(containerId, "mgconsole", "--execute", "RETURN 1;");
                    if (result.contains("1")) { log.info("Memgraph is ready"); return; }
                } else {
                    String result = docker.execInContainer(containerId, "cypher-shell", "-u", "neo4j", "-p", "benchmark", "RETURN 1;");
                    if (result.contains("1")) { log.info("Neo4j is ready"); return; }
                }
            } catch (Exception e) {
                if (i % 10 == 9) log.debug("{} not ready yet (attempt {}): {}", dbName, i + 1, e.getMessage());
            }
            sleep(2000);
        }
        throw new RuntimeException(dbName + " did not become ready in time");
    }

    @Override
    public void execute(DockerService docker, String containerId, String script, int hostPort) {
        String result;
        if ("memgraph".equals(dbName)) {
            result = docker.execWithStdin(containerId, script, "mgconsole");
        } else {
            result = docker.execWithStdin(containerId, script, "cypher-shell", "-u", "neo4j", "-p", "benchmark");
        }
        log.info("{} script executed: {}", dbName, result.substring(0, Math.min(200, result.length())));
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
