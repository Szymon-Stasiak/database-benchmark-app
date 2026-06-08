package com.dbagnets.backend.benchmark.driver.etcd;

import com.dbagnets.backend.benchmark.cascade.CascadeNode;
import com.dbagnets.backend.benchmark.datagen.GeneratedRow;
import com.dbagnets.backend.benchmark.driver.DeleteContext;
import com.dbagnets.backend.benchmark.driver.EngineDriver;
import com.dbagnets.backend.benchmark.driver.InsertContext;
import com.dbagnets.backend.benchmark.driver.ReadContext;
import com.dbagnets.backend.benchmark.registry.EntityIdRegistry.RegistryEntry;
import com.dbagnets.backend.benchmark.schema.LogicalEntity;
import com.dbagnets.backend.benchmark.timing.RecordedId;
import com.dbagnets.backend.benchmark.timing.TimedOperation;
import com.dbagnets.backend.entity.DatabaseEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    public TimedOperation insert(InsertContext ctx) {
        WebClient client = clientFor(ctx.hostAddress(), ctx.hostPort());

        long totalDbTimeNs = 0L;
        long totalRowsAffected = 0L;
        List<RecordedId> recordedIds = new ArrayList<>();

        long wireStart = System.nanoTime();
        for (CascadeNode node : ctx.plan().nodesInInsertOrder()) {
            List<GeneratedRow> rows = ctx.rowsByEntity().get(node.entityName());
            if (rows == null || rows.isEmpty()) continue;
            LogicalEntity entity = ctx.schema().requireEntity(node.entityName());
            EntityOutcome outcome = putAll(client, ctx, node, entity, rows);
            totalDbTimeNs += outcome.dbTimeNs;
            totalRowsAffected += outcome.rowsAffected;
            recordedIds.addAll(outcome.recordedIds);
            ctx.progress().onEntityFinished(node.entityName());
        }
        long wireTimeNs = System.nanoTime() - wireStart;

        return TimedOperation.builder()
                .dbTimeNs(totalDbTimeNs)
                .wireTimeNs(wireTimeNs)
                .rowsAffected(totalRowsAffected)
                .conflictsSkipped(0)
                .recordedIds(recordedIds)
                .build();
    }

    @Override
    public TimedOperation read(ReadContext ctx) {
        WebClient client = clientFor(ctx.hostAddress(), ctx.hostPort());
        String prefix = ctx.entityName().toLowerCase() + ":";

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsRead = 0L;

        long wireStart = System.nanoTime();
        for (int i = 0; i < ctx.targets().size(); i++) {
            RegistryEntry entry = ctx.targets().get(i);
            String key = prefix + entry.physicalId();
            try {
                long start = System.nanoTime();
                JsonNode resp = client.post()
                        .uri("/v3/kv/range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("key", b64(key)))
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .timeout(Duration.ofSeconds(15))
                        .block();
                long sampleNs = System.nanoTime() - start;
                samples[i] = sampleNs;
                totalDbTimeNs += sampleNs;
                if (resp != null && resp.path("count").asInt(0) > 0) rowsRead++;
            } catch (Exception ex) {
                log.warn("Etcd read failed {}: {}", key, ex.getMessage());
            }
        }
        long wireTimeNs = System.nanoTime() - wireStart;

        return TimedOperation.builder()
                .dbTimeNs(totalDbTimeNs)
                .wireTimeNs(wireTimeNs)
                .rowsAffected(rowsRead)
                .sampleDbTimeNs(samples)
                .build();
    }

    @Override
    public TimedOperation delete(DeleteContext ctx) {
        WebClient client = clientFor(ctx.hostAddress(), ctx.hostPort());
        String prefix = ctx.entityName().toLowerCase() + ":";

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsAffected = 0L;

        long wireStart = System.nanoTime();
        for (int i = 0; i < ctx.targets().size(); i++) {
            RegistryEntry entry = ctx.targets().get(i);
            String key = prefix + entry.physicalId();
            try {
                long start = System.nanoTime();
                JsonNode resp = client.post()
                        .uri("/v3/kv/deleterange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("key", b64(key)))
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .timeout(Duration.ofSeconds(15))
                        .block();
                long sampleNs = System.nanoTime() - start;
                samples[i] = sampleNs;
                totalDbTimeNs += sampleNs;
                if (resp != null && resp.path("deleted").asInt(0) > 0) rowsAffected++;
            } catch (Exception ex) {
                log.warn("Etcd delete failed {}: {}", key, ex.getMessage());
            }
        }
        long wireTimeNs = System.nanoTime() - wireStart;

        return TimedOperation.builder()
                .dbTimeNs(totalDbTimeNs)
                .wireTimeNs(wireTimeNs)
                .rowsAffected(rowsAffected)
                .sampleDbTimeNs(samples)
                .build();
    }

    private EntityOutcome putAll(WebClient client,
                                  InsertContext ctx,
                                  CascadeNode node,
                                  LogicalEntity entity,
                                  List<GeneratedRow> rows) {
        EntityOutcome outcome = new EntityOutcome();
        String prefix = node.entityName().toLowerCase() + ":";
        int total = rows.size();
        int totalBatches = Math.max(1, total);

        for (int i = 0; i < rows.size(); i++) {
            GeneratedRow row = rows.get(i);
            String key = prefix + row.logicalId();
            String value;
            try {
                value = objectMapper.writeValueAsString(serializable(entity, row));
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
                outcome.recordedIds.add(new RecordedId(node.entityName(), row.logicalId(), row.logicalId()));
            } catch (Exception ex) {
                log.warn("Etcd put failed {}: {}", key, ex.getMessage());
            }
            if ((i + 1) % 100 == 0 || (i + 1) == total) {
                ctx.progress().onBatch(node.entityName(), i + 1, totalBatches, i + 1, total);
            }
        }
        return outcome;
    }

    private Map<String, Object> serializable(LogicalEntity entity, GeneratedRow row) {
        Map<String, Object> doc = new LinkedHashMap<>();
        for (var entry : row.values().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof float[] arr) {
                List<Double> list = new ArrayList<>(arr.length);
                for (float f : arr) list.add((double) f);
                doc.put(entry.getKey(), list);
            } else if (value instanceof java.time.Instant ins) {
                doc.put(entry.getKey(), ins.toString());
            } else if (value instanceof java.time.LocalDate ld) {
                doc.put(entry.getKey(), ld.toString());
            } else {
                doc.put(entry.getKey(), value);
            }
        }
        return doc;
    }

    private String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private WebClient clientFor(String host, int port) {
        return WebClient.builder()
                .baseUrl("http://" + host + ":" + port)
                .build();
    }

    private static final class EntityOutcome {
        long dbTimeNs;
        long rowsAffected;
        List<RecordedId> recordedIds = new ArrayList<>();
    }
}
