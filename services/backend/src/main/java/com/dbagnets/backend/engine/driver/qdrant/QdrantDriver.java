package com.dbagnets.backend.engine.driver.qdrant;

import com.dbagnets.backend.engine.cascade.CascadeNode;
import com.dbagnets.backend.engine.datagen.GeneratedRow;
import com.dbagnets.backend.engine.driver.BatchSizes;
import com.dbagnets.backend.engine.driver.BulkInsertLoop;
import com.dbagnets.backend.engine.driver.DeleteContext;
import com.dbagnets.backend.engine.driver.EngineDriver;
import com.dbagnets.backend.engine.driver.EntityOutcome;
import com.dbagnets.backend.engine.driver.HttpClients;
import com.dbagnets.backend.engine.driver.InsertContext;
import com.dbagnets.backend.engine.driver.InsertOuterLoop;
import com.dbagnets.backend.engine.driver.PerTargetLoop;
import com.dbagnets.backend.engine.driver.ReadContext;
import com.dbagnets.backend.engine.driver.VectorAttributes;
import com.dbagnets.backend.engine.scenario.KnnParams;
import com.dbagnets.backend.engine.scenario.ResultCanonicalizer;
import com.dbagnets.backend.engine.scenario.ScenarioContext;
import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalEntity;
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
    public TimedOperation insert(InsertContext ctx) throws Exception {
        WebClient client = HttpClients.basic(ctx.hostAddress(), ctx.hostPort());
        return InsertOuterLoop.run(ctx, (node, rows) -> {
            LogicalEntity entity = ctx.schema().requireEntity(node.entityName());
            String collection = node.entityName().toLowerCase();
            LogicalAttribute vectorAttr = VectorAttributes.find(entity);
            if (vectorAttr == null) {
                log.debug("Qdrant: skipping entity {} without VECTOR attribute", node.entityName());
                return null;
            }
            return upsertEntity(client, ctx, node, collection, vectorAttr, rows);
        });
    }

    @Override
    public TimedOperation read(ReadContext ctx) {
        WebClient client = HttpClients.basic(ctx.hostAddress(), ctx.hostPort());
        String collection = ctx.entityName().toLowerCase();
        return PerTargetLoop.run(ctx.targets(), "Qdrant read for",
                e -> collection + "/" + normalizeId(e.physicalId() != null ? e.physicalId() : e.logicalId()),
                entry -> {
                    Object id = normalizeId(entry.physicalId() != null ? entry.physicalId() : entry.logicalId());
                    JsonNode response = client.post().uri("/collections/{c}/points", collection).contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("ids", List.of(id), "with_payload", true, "with_vector", false)).retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofSeconds(15)).block();
                    JsonNode result = response != null ? response.path("result") : null;
                    return result != null && result.isArray() && !result.isEmpty() ? 1 : 0;
                });
    }

    @Override
    public TimedOperation delete(DeleteContext ctx) {
        WebClient client = HttpClients.basic(ctx.hostAddress(), ctx.hostPort());
        String collection = ctx.entityName().toLowerCase();
        return PerTargetLoop.run(ctx.targets(), "Qdrant delete for",
                e -> collection + "/" + normalizeId(e.physicalId() != null ? e.physicalId() : e.logicalId()),
                entry -> {
                    Object id = normalizeId(entry.physicalId() != null ? entry.physicalId() : entry.logicalId());
                    JsonNode response = client.post().uri("/collections/{c}/points/delete?wait=true", collection).contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("points", List.of(id))).retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofSeconds(15)).block();
                    String status = response != null ? response.path("result").path("status").asText("") : "";
                    return "completed".equalsIgnoreCase(status) || "acknowledged".equalsIgnoreCase(status) ? 1 : 0;
                });
    }

    private EntityOutcome upsertEntity(WebClient client, InsertContext ctx, CascadeNode node, String collection, LogicalAttribute vectorAttr, List<GeneratedRow> rows) throws Exception {
        BulkInsertLoop.Config config = new BulkInsertLoop.Config(
                BatchSizes.effective(ctx, 1_000),
                engine(),
                false,
                "Qdrant upsert failed for {} batch {}: {}",
                null,
                collection);
        return BulkInsertLoop.run(ctx, node, rows, config, (slice, batchIndex, totalBatches) -> {
            List<Map<String, Object>> points = new ArrayList<>(slice.size());
            for (GeneratedRow row : slice) {
                Object vector = row.get(vectorAttr.name());
                if (!(vector instanceof float[] arr)) continue;
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("id", normalizeId(row.logicalId()));
                point.put("vector", VectorAttributes.toList(arr));
                point.put("payload", payloadOf(row, vectorAttr.name()));
                points.add(point);
            }
            if (points.isEmpty()) return 0L;
            client.put().uri("/collections/{c}/points?wait=true", collection).contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("points", points)).retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofSeconds(30)).block();
            return slice.size();
        });
    }

    private Map<String, Object> payloadOf(GeneratedRow row, String vectorColumn) {
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

    @Override
    public ScenarioOutcome runScenario(ScenarioContext ctx) throws Exception {
        if (!(ctx.params() instanceof KnnParams knn)) {
            throw new UnsupportedOperationException(engine() + " only supports VECTOR_KNN");
        }
        WebClient client = HttpClients.basic(ctx.hostAddress(), ctx.hostPort());
        String collection = knn.entityName().toLowerCase();
        List<Float> queryVec = new ArrayList<>(knn.queryVector().length);
        for (double v : knn.queryVector()) queryVec.add((float) v);
        Map<String, Object> body = Map.of("vector", queryVec, "limit", knn.topK(), "with_payload", false);

        List<Map<String, Object>> hits = new ArrayList<>();
        return com.dbagnets.backend.engine.driver.ScenarioTimings.execute(() -> {
            JsonNode response = client.post().uri("/collections/{c}/points/search", collection).contentType(MediaType.APPLICATION_JSON).bodyValue(body).retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(30));
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
            return ResultCanonicalizer.build(hits, hits.size());
        });
    }
}
