package com.dbagnets.backend.benchmark.driver.couchdb;

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
import com.dbagnets.backend.domain.DatabaseEngine;
import com.fasterxml.jackson.databind.JsonNode;
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
public class CouchdbDriver implements EngineDriver {

    private static final String AUTH = "Basic " + Base64.getEncoder()
            .encodeToString("admin:benchmark".getBytes(StandardCharsets.UTF_8));

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.COUCHDB;
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
            String db = node.entityName().toLowerCase();
            ensureDb(client, db);
            LogicalEntity entity = ctx.schema().requireEntity(node.entityName());
            EntityOutcome outcome = bulkInsert(client, ctx, node, entity, db, rows);
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
        String db = ctx.entityName().toLowerCase();

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsRead = 0L;

        long wireStart = System.nanoTime();
        for (int i = 0; i < ctx.targets().size(); i++) {
            RegistryEntry entry = ctx.targets().get(i);
            try {
                long start = System.nanoTime();
                JsonNode resp = client.get()
                        .uri("/{db}/{id}", db, entry.physicalId())
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .timeout(Duration.ofSeconds(15))
                        .block();
                long sampleNs = System.nanoTime() - start;
                samples[i] = sampleNs;
                totalDbTimeNs += sampleNs;
                if (resp != null && resp.has("_id")) rowsRead++;
            } catch (Exception ex) {
                log.warn("CouchDB read failed {}/{}: {}", db, entry.physicalId(), ex.getMessage());
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
        String db = ctx.entityName().toLowerCase();

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsAffected = 0L;

        long wireStart = System.nanoTime();
        for (int i = 0; i < ctx.targets().size(); i++) {
            RegistryEntry entry = ctx.targets().get(i);
            try {
                long start = System.nanoTime();
                JsonNode head = client.get()
                        .uri("/{db}/{id}", db, entry.physicalId())
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .timeout(Duration.ofSeconds(15))
                        .block();
                String rev = head != null ? head.path("_rev").asText("") : "";
                JsonNode resp = client.delete()
                        .uri(uri -> uri.path("/{db}/{id}").queryParam("rev", rev).build(db, entry.physicalId()))
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .timeout(Duration.ofSeconds(15))
                        .block();
                long sampleNs = System.nanoTime() - start;
                samples[i] = sampleNs;
                totalDbTimeNs += sampleNs;
                if (resp != null && resp.path("ok").asBoolean(false)) rowsAffected++;
            } catch (Exception ex) {
                log.warn("CouchDB delete failed {}/{}: {}", db, entry.physicalId(), ex.getMessage());
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

    private void ensureDb(WebClient client, String db) {
        try {
            client.put()
                    .uri("/{db}", db)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(15))
                    .onErrorResume(ex -> reactor.core.publisher.Mono.empty())
                    .block();
        } catch (Exception ignore) {
        }
    }

    private EntityOutcome bulkInsert(WebClient client,
                                     InsertContext ctx,
                                     CascadeNode node,
                                     LogicalEntity entity,
                                     String db,
                                     List<GeneratedRow> rows) {
        EntityOutcome outcome = new EntityOutcome();
        int batchSize = effectiveBatchSize(ctx);
        int totalBatches = Math.max(1, (int) Math.ceil((double) rows.size() / batchSize));
        int batchIndex = 0;

        for (int from = 0; from < rows.size(); from += batchSize) {
            int to = Math.min(from + batchSize, rows.size());
            List<GeneratedRow> slice = rows.subList(from, to);
            List<Map<String, Object>> docs = new ArrayList<>(slice.size());
            for (GeneratedRow row : slice) {
                docs.add(toDocument(entity, row));
            }
            try {
                long start = System.nanoTime();
                client.post()
                        .uri("/{db}/_bulk_docs", db)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("docs", docs))
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .timeout(Duration.ofSeconds(60))
                        .block();
                outcome.dbTimeNs += System.nanoTime() - start;
                outcome.rowsAffected += slice.size();
                slice.forEach(r -> outcome.recordedIds.add(new RecordedId(node.entityName(), r.logicalId(), r.logicalId())));
            } catch (Exception ex) {
                log.warn("CouchDB bulk insert failed on {} batch {}: {}", db, batchIndex, ex.getMessage());
            }
            batchIndex++;
            ctx.progress().onBatch(node.entityName(), batchIndex, totalBatches, to, rows.size());
        }
        return outcome;
    }

    private Map<String, Object> toDocument(LogicalEntity entity, GeneratedRow row) {
        Map<String, Object> doc = new LinkedHashMap<>();
        entity.primaryKey().ifPresent(pk -> doc.put("_id", String.valueOf(row.get(pk.name()))));
        for (Map.Entry<String, Object> entry : row.values().entrySet()) {
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

    private WebClient clientFor(String host, int port) {
        return WebClient.builder()
                .baseUrl("http://" + host + ":" + port)
                .defaultHeader("Authorization", AUTH)
                .build();
    }

    private int effectiveBatchSize(InsertContext ctx) {
        return switch (ctx.mode()) {
            case SINGLE -> 1;
            case BATCH -> Math.max(1, ctx.batchSize());
            case BULK -> Math.max(1, ctx.batchSize() > 0 ? ctx.batchSize() : 1_000);
        };
    }

    private static final class EntityOutcome {
        long dbTimeNs;
        long rowsAffected;
        List<RecordedId> recordedIds = new ArrayList<>();
    }
}
