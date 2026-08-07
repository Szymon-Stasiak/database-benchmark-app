package com.dbagnets.backend.infrastructure.docker.strategy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Properties;

import com.dbagnets.backend.infrastructure.docker.DockerService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class QuestdbScriptStrategy implements ScriptExecutionStrategy {

    private static final String USER = "admin";
    private static final String PASSWORD = "quest";
    private static final String DATABASE = "qdb";

    @Override
    public void waitForReady(DockerService docker, String containerId, int hostPort) {
        for (int i = 0; i < 60; i++) {
            try (Connection conn = connect(hostPort);
                    Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT 1");
                log.info("QuestDB ready on PG-wire port {}", hostPort);
                return;
            } catch (Exception ignore) {
                sleep(2000);
            }
        }
        throw new RuntimeException("QuestDB did not become ready on port " + hostPort);
    }

    @Override
    public void execute(DockerService docker, String containerId, String script, int hostPort) {
        try (Connection conn = connect(hostPort);
                Statement stmt = conn.createStatement()) {
            for (String raw : script.split(";")) {
                String sql = raw.trim();
                if (sql.isEmpty() || sql.startsWith("--")) continue;
                try {
                    stmt.execute(sql);
                } catch (Exception ex) {
                    log.warn("QuestDB statement failed: {} — {}", abbreviate(sql), ex.getMessage());
                }
            }
            log.info("QuestDB script executed via PG-wire");
        } catch (Exception ex) {
            throw new RuntimeException("QuestDB init script failed: " + ex.getMessage(), ex);
        }
    }

    private Connection connect(int hostPort) throws Exception {
        Properties props = new Properties();
        props.setProperty("user", USER);
        props.setProperty("password", PASSWORD);
        props.setProperty("preferQueryMode", "simple");
        return DriverManager.getConnection(
                "jdbc:postgresql://localhost:" + hostPort + "/" + DATABASE, props);
    }

    private String abbreviate(String s) {
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
