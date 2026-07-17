package com.dbagnets.backend.infrastructure.docker.strategy;

import com.dbagnets.backend.infrastructure.docker.DockerService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EtcdScriptStrategy implements ScriptExecutionStrategy {

    @Override
    public void waitForReady(DockerService docker, String containerId, int hostPort) {
        for (int i = 0; i < 30; i++) {
            String result = docker.execInContainer(containerId, "etcdctl", "endpoint", "health");
            if (result.contains("healthy")) { log.info("etcd is ready"); return; }
            sleep(2000);
        }
        throw new RuntimeException("etcd did not become ready in time");
    }

    @Override
    public void execute(DockerService docker, String containerId, String script, int hostPort) {
        for (String line : script.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] parts = trimmed.split("\\s+");
            String result = docker.execInContainer(containerId, parts);
            log.debug("etcd command result: {}", result);
        }
        log.info("etcd script executed");
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
