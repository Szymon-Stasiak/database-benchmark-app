package com.dbagnets.backend.engine.driver.engines.etcd;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.dbagnets.backend.domain.DatabaseEngine;
import com.dbagnets.backend.engine.cascade.CascadeNode;
import com.dbagnets.backend.engine.datagen.GeneratedRow;
import com.dbagnets.backend.engine.driver.api.DeleteContext;
import com.dbagnets.backend.engine.driver.api.EngineDriver;
import com.dbagnets.backend.engine.driver.api.EntityOutcome;
import com.dbagnets.backend.engine.driver.api.InsertContext;
import com.dbagnets.backend.engine.driver.api.ReadContext;
import com.dbagnets.backend.engine.driver.support.DriverValues;
import com.dbagnets.backend.engine.driver.support.HttpClients;
import com.dbagnets.backend.engine.driver.support.InsertOuterLoop;
import com.dbagnets.backend.engine.driver.support.PerTargetLoop;
import com.dbagnets.backend.engine.timing.RecordedId;
import com.dbagnets.backend.engine.timing.TimedOperation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class EtcdDriver implements EngineDriver {

    private final ObjectMapper objectMapper;

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.ETCD;
    }

    @Override
    public TimedOperation insert(InsertContext ctx) throws Exception {
        WebClient client = clientFor(ctx.hostAddress(), ctx.hostPort());
        return InsertOuterLoop.run(ctx, (node, rows) -> putAll(client, ctx, node, rows));
    }

    @Override
    public TimedOperation read(ReadContext ctx) {
        WebClient client = clientFor(ctx.hostAddress(), ctx.hostPort());
        String prefix = ctx.entityName().toLowerCase() + ":";
        return PerTargetLoop.run(
                ctx.targets(),
                "Etcd read",
                e -> prefix + e.physicalId(),
                entry -> {
                    JsonNode resp =
                            client.post()
                                    .uri("/v3/kv/range")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .bodyValue(Map.of("key", b64(prefix + entry.physicalId())))
                                    .retrieve()
                                    .bodyToMono(JsonNode.class)
                                    .timeout(Duration.ofSeconds(15))
                                    .block();
                    return resp != null && resp.path("count").asInt(0) > 0 ? 1 : 0;
                });
    }

    @Override
    public TimedOperation delete(DeleteContext ctx) {
        WebClient client = clientFor(ctx.hostAddress(), ctx.hostPort());
        String prefix = ctx.entityName().toLowerCase() + ":";
        return PerTargetLoop.run(
                ctx.targets(),
                "Etcd delete",
                e -> prefix + e.physicalId(),
                entry -> {
                    JsonNode resp =
                            client.post()
                                    .uri("/v3/kv/deleterange")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .bodyValue(Map.of("key", b64(prefix + entry.physicalId())))
                                    .retrieve()
                                    .bodyToMono(JsonNode.class)
                                    .timeout(Duration.ofSeconds(15))
                                    .block();
                    return resp != null && resp.path("deleted").asInt(0) > 0 ? 1 : 0;
                });
    }

    private EntityOutcome putAll(
            WebClient client,
            InsertContext ctx,
            CascadeNode node,
            java.util.List<GeneratedRow> rows) {
        EntityOutcome outcome = new EntityOutcome();
        String prefix = node.entityName().toLowerCase() + ":";
        int total = rows.size();
        int totalBatches = Math.max(1, total);

        for (int i = 0; i < rows.size(); i++) {
            GeneratedRow row = rows.get(i);
            String key = prefix + row.logicalId();
            String value;
            try {
                value = objectMapper.writeValueAsString(DriverValues.rowToMap(row));
            } catch (Exception ex) {
                log.warn("Etcd serialization failed for {}: {}", key, ex.getMessage());
                continue;
            }
            try {
                long start = System.nanoTime();
                client.post()
                        .uri("/v3/kv/put")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("key", b64(key), "value", b64(value)))
                        .retrieve()
                        .toBodilessEntity()
                        .timeout(Duration.ofSeconds(15))
                        .block();
                outcome.dbTimeNs += System.nanoTime() - start;
                outcome.rowsAffected++;
                outcome.recordedIds.add(
                        new RecordedId(node.entityName(), row.logicalId(), row.logicalId()));
            } catch (Exception ex) {
                log.warn("Etcd put failed {}: {}", key, ex.getMessage());
            }
            if ((i + 1) % 100 == 0 || (i + 1) == total) {
                ctx.progress().onBatch(node.entityName(), i + 1, totalBatches, i + 1, total);
            }
        }
        return outcome;
    }

    private String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private WebClient clientFor(String host, int port) {
        return HttpClients.basic(host, port);
    }
}
