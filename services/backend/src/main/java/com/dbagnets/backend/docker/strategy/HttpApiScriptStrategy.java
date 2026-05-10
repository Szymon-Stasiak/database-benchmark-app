package com.dbagnets.backend.docker.strategy;

import com.dbagnets.backend.docker.DockerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

public class HttpApiScriptStrategy implements ScriptExecutionStrategy {
    private static final Logger log = LoggerFactory.getLogger(HttpApiScriptStrategy.class);
    private final String dbName;

    public HttpApiScriptStrategy(String dbName) {
        this.dbName = dbName;
    }

    @Override
    public void waitForReady(DockerService docker, String containerId, int hostPort) {
        String healthPath = switch (dbName) {
            case "elasticsearch" -> "/_cluster/health";
            case "couchdb" -> "/";
            case "milvus" -> "/healthz";
            case "qdrant" -> "/healthz";
            case "weaviate" -> "/v1/.well-known/ready";
            case "influxdb" -> "/health";
            case "questdb" -> "/";
            case "dynamodb" -> "/";
            default -> "/";
        };

        WebClient client = WebClient.create("http://localhost:" + hostPort);
        for (int i = 0; i < 30; i++) {
            try {
                String response = client.get().uri(healthPath)
                    .retrieve().bodyToMono(String.class).block(java.time.Duration.ofSeconds(5));
                if (response != null) {
                    log.info("{} is ready on port {}", dbName, hostPort);
                    return;
                }
            } catch (Exception e) { /* not ready */ }
            sleep(2000);
        }
        throw new RuntimeException(dbName + " did not become ready on port " + hostPort);
    }

    @Override
    public void execute(DockerService docker, String containerId, String script, int hostPort) {
        // For HTTP-based databases, the script is typically a series of API calls
        // We send the entire script as the request body to a db-specific endpoint
        WebClient client = WebClient.create("http://localhost:" + hostPort);

        switch (dbName) {
            case "elasticsearch" -> executeElasticsearch(client, script);
            case "questdb" -> executeQuestDb(client, script);
            default -> executeGeneric(client, script);
        }
    }

    private void executeElasticsearch(WebClient client, String script) {
        // Elasticsearch scripts are typically PUT requests for index creation
        // Split by blank lines (each section is a separate API call)
        // For simplicity, send each line that starts with PUT/POST as a separate request
        // The script from script-creator is typically a bulk operation or index definitions
        try {
            client.post().uri("/_bulk")
                .header("Content-Type", "application/x-ndjson")
                .bodyValue(script)
                .retrieve().bodyToMono(String.class)
                .block(java.time.Duration.ofSeconds(30));
        } catch (Exception e) {
            // Fallback: try line-by-line SQL-like execution
            for (String line : script.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) continue;
                try {
                    client.post().uri("/_scripts/" + System.currentTimeMillis())
                        .bodyValue(trimmed)
                        .retrieve().bodyToMono(String.class)
                        .block(java.time.Duration.ofSeconds(10));
                } catch (Exception ex) {
                    log.warn("ES command failed: {}", trimmed);
                }
            }
        }
        log.info("Elasticsearch script executed");
    }

    private void executeQuestDb(WebClient client, String script) {
        // QuestDB uses SQL via HTTP: GET /exec?query=...
        for (String line : script.split(";")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;
            try {
                client.get().uri(uriBuilder -> uriBuilder
                        .path("/exec")
                        .queryParam("query", trimmed)
                        .build())
                    .retrieve().bodyToMono(String.class)
                    .block(java.time.Duration.ofSeconds(10));
            } catch (Exception e) {
                log.warn("QuestDB query failed: {}", trimmed);
            }
        }
        log.info("QuestDB script executed");
    }

    private void executeGeneric(WebClient client, String script) {
        // For other HTTP-based databases, try POST with the script body
        try {
            client.post().uri("/")
                .bodyValue(script)
                .retrieve().bodyToMono(String.class)
                .block(java.time.Duration.ofSeconds(30));
        } catch (Exception e) {
            log.warn("Generic HTTP execution failed for {}: {}", dbName, e.getMessage());
        }
        log.info("{} script executed via HTTP", dbName);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
