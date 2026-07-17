package com.dbagnets.backend.engine.driver.qdrant;

import com.dbagnets.backend.engine.cascade.CascadeNode;
import com.dbagnets.backend.engine.datagen.GeneratedRow;
import com.dbagnets.backend.engine.driver.DeleteContext;
import com.dbagnets.backend.engine.driver.EngineDriver;
import com.dbagnets.backend.engine.driver.InsertContext;
import com.dbagnets.backend.engine.driver.ReadContext;
import com.dbagnets.backend.engine.registry.EntityIdRegistry.RegistryEntry;
import com.dbagnets.backend.engine.scenario.AggregateParams;
import com.dbagnets.backend.engine.scenario.KnnParams;
import com.dbagnets.backend.engine.scenario.RangeParams;
import com.dbagnets.backend.engine.scenario.ResultCanonicalizer;
import com.dbagnets.backend.engine.scenario.ScenarioContext;
import com.dbagnets.backend.engine.scenario.ScenarioResult;
import com.dbagnets.backend.engine.scenario.TraversalParams;
import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalDataType;
import com.dbagnets.backend.engine.schema.LogicalEntity;
import com.dbagnets.backend.engine.timing.RecordedId;
import com.dbagnets.backend.engine.timing.TimedOperation;
import com.dbagnets.backend.domain.DatabaseEngine;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class QdrantDriver implements EngineDriver {

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.QDRANT;
    }

    @Override
    public TimedOperation insert(InsertContext ctx) {
        WebClient client = WebClient.builder()
                .baseUrl("http://" + ctx.hostAddress() + ":" + ctx.hostPort())
                .build();

        long totalDbTimeNs = 0L;
        long totalRowsAffected = 0L;
        List<RecordedId> recordedIds = new ArrayList<>();

        long wireStart = System.nanoTime();
        for (CascadeNode node : ctx.plan().nodesInInsertOrder()) {
            List<GeneratedRow> rows = ctx.rowsByEntity().get(node.entityName());
            if (rows == null || rows.isEmpty()) continue;
            LogicalEntity entity = ctx.schema().requireEntity(node.entityName());
            String collection = node.entityName().toLowerCase();
            LogicalAttribute vectorAttr = vectorAttribute(entity);
            if (vectorAttr == null) {
                log.debug("Qdrant: skipping entity {} without VECTOR attribute", node.entityName());
                continue;
            }
            EntityWriteOutcome outcome = upsertEntity(client, ctx, node, entity, collection, vectorAttr, rows);
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
        WebClient client = WebClient.builder()
                .baseUrl("http://" + ctx.hostAddress() + ":" + ctx.hostPort())
                .build();
        String collection = ctx.entityName().toLowerCase();

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsRead = 0L;

        long wireStart = System.nanoTime();
        for (int i = 0; i < ctx.targets().size(); i++) {
            RegistryEntry entry = ctx.targets().get(i);
            Object id = normalizeId(entry.physicalId() != null ? entry.physicalId() : entry.logicalId());
            try {
                long start = System.nanoTime();
                JsonNode response = client.post()
                        .uri("/collections/{c}/points", collection)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("ids", List.of(id), "with_payload", true, "with_vector", false))
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .timeout(Duration.ofSeconds(15))
                        .block();
                long sampleNs = System.nanoTime() - start;
                samples[i] = sampleNs;
                totalDbTimeNs += sampleNs;
                if (response != null) {
                    JsonNode result = response.path("result");
                    if (result.isArray() && result.size() > 0) rowsRead++;
                }
            } catch (Exception ex) {
                log.warn("Qdrant read failed for {}/{}: {}", collection, id, ex.getMessage());
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
        WebClient client = WebClient.builder()
                .baseUrl("http://" + ctx.hostAddress() + ":" + ctx.hostPort())
                .build();
        String collection = ctx.entityName().toLowerCase();

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsAffected = 0L;

        long wireStart = System.nanoTime();
        for (int i = 0; i < ctx.targets().size(); i++) {
            RegistryEntry entry = ctx.targets().get(i);
            Object id = normalizeId(entry.physicalId() != null ? entry.physicalId() : entry.logicalId());
            try {
                long start = System.nanoTime();
                JsonNode response = client.post()
                        .uri("/collections/{c}/points/delete?wait=true", collection)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("points", List.of(id)))
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .timeout(Duration.ofSeconds(15))
                        .block();
                long sampleNs = System.nanoTime() - start;
                samples[i] = sampleNs;
                totalDbTimeNs += sampleNs;
                if (response != null) {
                    String status = response.path("result").path("status").asText("");
                    if ("completed".equalsIgnoreCase(status) || "acknowledged".equalsIgnoreCase(status)) {
                        rowsAffected++;
                    }
                }
            } catch (Exception ex) {
                log.warn("Qdrant delete failed for {}/{}: {}", collection, id, ex.getMessage());
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

    private EntityWriteOutcome upsertEntity(WebClient client,
                                             InsertContext ctx,
                                             CascadeNode node,
                                             LogicalEntity entity,
                                             String collection,
                                             LogicalAttribute vectorAttr,
                                             List<GeneratedRow> rows) {
        EntityWriteOutcome outcome = new EntityWriteOutcome();
        int batchSize = effectiveBatchSize(ctx);
        int totalBatches = Math.max(1, (int) Math.ceil((double) rows.size() / batchSize));
        int batchIndex = 0;

        for (int from = 0; from < rows.size(); from += batchSize) {
            int to = Math.min(from + batchSize, rows.size());
            List<GeneratedRow> slice = rows.subList(from, to);
            List<Map<String, Object>> points = new ArrayList<>(slice.size());
            for (GeneratedRow row : slice) {
                Object vector = row.get(vectorAttr.name());
                if (!(vector instanceof float[] arr)) continue;
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("id", normalizeId(row.logicalId()));
                point.put("vector", floatArrayToList(arr));
                point.put("payload", payloadOf(entity, row, vectorAttr.name()));
                points.add(point);
            }
            if (points.isEmpty()) continue;
            try {
                long start = System.nanoTime();
                client.put()
                        .uri("/collections/{c}/points?wait=true", collection)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("points", points))
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .timeout(Duration.ofSeconds(30))
                        .block();
                outcome.dbTimeNs += System.nanoTime() - start;
                outcome.rowsAffected += slice.size();
                slice.forEach(r -> outcome.recordedIds.add(new RecordedId(node.entityName(), r.logicalId(), r.logicalId())));
            } catch (Exception ex) {
                log.warn("Qdrant upsert failed for {} batch {}: {}", collection, batchIndex, ex.getMessage());
            }
            batchIndex++;
            ctx.progress().onBatch(node.entityName(), batchIndex, totalBatches, to, rows.size());
        }
        return outcome;
    }

    private LogicalAttribute vectorAttribute(LogicalEntity entity) {
        return entity.attributes().stream()
                .filter(a -> a.dataType() == LogicalDataType.VECTOR)
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> payloadOf(LogicalEntity entity, GeneratedRow row, String vectorColumn) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.values().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(vectorColumn)) continue;
            Object value = entry.getValue();
            if (value instanceof java.time.Instant ins) {
                payload.put(entry.getKey(), ins.toString());
            } else if (value instanceof java.time.LocalDate ld) {
                payload.put(entry.getKey(), ld.toString());
            } else {
                payload.put(entry.getKey(), value);
            }
        }
        return payload;
    }

    private Object normalizeId(String logicalId) {
        try {
            return UUID.fromString(logicalId).toString();
        } catch (IllegalArgumentException ex) {
            return logicalId;
        }
    }

    private List<Float> floatArrayToList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) list.add(f);
        return list;
    }

    private int effectiveBatchSize(InsertContext ctx) {
        return switch (ctx.mode()) {
            case SINGLE -> 1;
            case BATCH -> Math.max(1, ctx.batchSize());
            case BULK -> Math.max(1, ctx.batchSize() > 0 ? ctx.batchSize() : 1_000);
        };
    }

    @Override
    public ScenarioOutcome runScenario(ScenarioContext ctx) {
        if (!(ctx.params() instanceof KnnParams knn)) {
            throw new UnsupportedOperationException(engine() + " only supports VECTOR_KNN");
        }
        if (ctx.params() instanceof AggregateParams || ctx.params() instanceof RangeParams
                || ctx.params() instanceof TraversalParams) {
            throw new UnsupportedOperationException(engine() + " does not support " + ctx.type());
        }
        WebClient client = WebClient.builder()
                .baseUrl("http://" + ctx.hostAddress() + ":" + ctx.hostPort())
                .build();

        String collection = knn.entityName().toLowerCase();
        List<Float> queryVec = new ArrayList<>(knn.queryVector().length);
        for (double v : knn.queryVector()) queryVec.add((float) v);

        Map<String, Object> body = Map.of(
                "vector", queryVec,
                "limit", knn.topK(),
                "with_payload", false
        );

        long wireStart = System.nanoTime();
        long dbStart = System.nanoTime();
        JsonNode response = client.post()
                .uri("/collections/{c}/points/search", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(30));
        long dbTimeNs = System.nanoTime() - dbStart;
        long wireTimeNs = System.nanoTime() - wireStart;

        List<Map<String, Object>> hits = new ArrayList<>();
        if (response != null && response.has("result") && response.get("result").isArray()) {
            for (JsonNode hit : response.get("result")) {
                String id = hit.has("id") ? hit.get("id").asText() : null;
                double score = hit.has("score") ? hit.get("score").asDouble() : 0.0;
                if (id != null) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", id);
                    entry.put("score", score);
                    hits.add(entry);
                }
            }
        }

        ScenarioResult result = ResultCanonicalizer.build(hits, hits.size());
        TimedOperation timed = TimedOperation.builder()
                .dbTimeNs(dbTimeNs)
                .wireTimeNs(wireTimeNs)
                .rowsAffected(hits.size())
                .sampleDbTimeNs(new long[] { dbTimeNs })
                .build();
        return new ScenarioOutcome(timed, result);
    }

    private static final class EntityWriteOutcome {
        long dbTimeNs;
        long rowsAffected;
        List<RecordedId> recordedIds = new ArrayList<>();
    }
}
