package com.dbagnets.backend.infrastructure.docker.strategy;

import com.dbagnets.backend.infrastructure.docker.DockerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Slf4j
public class HttpApiScriptStrategy implements ScriptExecutionStrategy {

    private static final int READINESS_MAX_ATTEMPTS = 30;
    private static final long READINESS_POLL_INTERVAL_MS = 2000L;
    private static final Duration READINESS_REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CHUNK_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration FALLBACK_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static final int MAX_BUFFER_MB = 16;
    private static final int BYTES_PER_MB = 1024 * 1024;
    private static final int BULK_CHUNK_MAX_BYTES = 1 * BYTES_PER_MB;
    private static final int BULK_LINES_PER_OP = 2;

    private static final String DEFAULT_HEALTH_PATH = "/";
    private static final String ELASTICSEARCH = "elasticsearch";

    private final String dbName;

    @Override
    public void waitForReady(DockerService docker, String containerId, int hostPort) {
        String healthPath = healthPathFor(dbName);
        WebClient client = buildClient(hostPort);

        for (int i = 0; i < READINESS_MAX_ATTEMPTS; i++) {
            try {
                String response = client.get().uri(healthPath)
                    .retrieve().bodyToMono(String.class).block(READINESS_REQUEST_TIMEOUT);
                if (response != null) {
                    log.info("{} is ready on port {}", dbName, hostPort);
                    return;
                }
            } catch (Exception e) {
                /* not ready yet */
            }
            sleep(READINESS_POLL_INTERVAL_MS);
        }
        throw new RuntimeException(dbName + " did not become ready on port " + hostPort);
    }

    @Override
    public void execute(DockerService docker, String containerId, String script, int hostPort) {
        WebClient client = buildClient(hostPort);

        switch (dbName) {
            case ELASTICSEARCH -> executeElasticsearch(client, script);
            default -> executeGeneric(client, script);
        }
    }

    private void executeElasticsearch(WebClient client, String script) {
        List<String> chunks = chunkNdjson(script, BULK_CHUNK_MAX_BYTES);
        log.info("Elasticsearch: sending {} bulk chunk(s)", chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            try {
                client.post().uri("/_bulk")
                    .header("Content-Type", "application/x-ndjson")
                    .bodyValue(chunk)
                    .retrieve().bodyToMono(String.class)
                    .block(CHUNK_REQUEST_TIMEOUT);
            } catch (Exception e) {
                log.warn("Bulk chunk {}/{} failed ({} bytes): {}", i + 1, chunks.size(), chunk.length(), e.getMessage());
                sendLineByLineFallback(client, chunk);
            }
        }
        log.info("Elasticsearch script executed");
    }

    private void sendLineByLineFallback(WebClient client, String chunk) {
        for (String line : chunk.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) continue;
            try {
                client.post().uri("/_scripts/" + trimmed.hashCode())
                    .bodyValue(trimmed)
                    .retrieve().bodyToMono(String.class)
                    .block(FALLBACK_REQUEST_TIMEOUT);
            } catch (Exception ex) {
                log.warn("ES command failed: {}", trimmed);
            }
        }
    }

    private void executeGeneric(WebClient client, String script) {
        try {
            client.post().uri("/")
                .bodyValue(script)
                .retrieve().bodyToMono(String.class)
                .block(CHUNK_REQUEST_TIMEOUT);
        } catch (Exception e) {
            log.warn("Generic HTTP execution failed for {}: {}", dbName, e.getMessage());
        }
        log.info("{} script executed via HTTP", dbName);
    }

    private WebClient buildClient(int hostPort) {
        return WebClient.builder()
            .baseUrl("http://localhost:" + hostPort)
            .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_BUFFER_MB * BYTES_PER_MB))
            .build();
    }

    private String healthPathFor(String dbName) {
        return switch (dbName) {
            case ELASTICSEARCH -> "/_cluster/health";
            case "couchdb" -> "/";
            case "qdrant" -> "/healthz";
            case "weaviate" -> "/v1/.well-known/ready";
            case "influxdb" -> "/health";
            case "dynamodb" -> "/";
            default -> DEFAULT_HEALTH_PATH;
        };
    }

    private List<String> chunkNdjson(String script, int maxBytes) {
        List<String> chunks = new ArrayList<>();
        String[] lines = script.split("\n", -1);
        StringBuilder current = new StringBuilder();
        int linesInCurrent = 0;

        for (String line : lines) {
            int lineBytes = line.length() + 1;
            boolean atOpBoundary = linesInCurrent % BULK_LINES_PER_OP == 0;
            if (current.length() + lineBytes > maxBytes && current.length() > 0 && atOpBoundary) {
                chunks.add(current.toString());
                current.setLength(0);
                linesInCurrent = 0;
            }
            current.append(line).append('\n');
            linesInCurrent++;
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
