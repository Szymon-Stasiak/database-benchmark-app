package com.dbagnets.backend.benchmark.driver.weaviate;

import com.dbagnets.backend.benchmark.cascade.CascadeNode;
import com.dbagnets.backend.benchmark.datagen.GeneratedRow;
import com.dbagnets.backend.benchmark.driver.DeleteContext;
import com.dbagnets.backend.benchmark.driver.EngineDriver;
import com.dbagnets.backend.benchmark.driver.InsertContext;
import com.dbagnets.backend.benchmark.driver.ReadContext;
import com.dbagnets.backend.benchmark.registry.EntityIdRegistry.RegistryEntry;
import com.dbagnets.backend.benchmark.schema.LogicalAttribute;
import com.dbagnets.backend.benchmark.schema.LogicalDataType;
import com.dbagnets.backend.benchmark.schema.LogicalEntity;
import com.dbagnets.backend.benchmark.timing.RecordedId;
import com.dbagnets.backend.benchmark.timing.TimedOperation;
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
public class WeaviateDriver implements EngineDriver {

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.WEAVIATE;
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
            String className = capitalize(node.entityName());
            LogicalAttribute vectorAttr = vectorAttribute(entity);
            EntityOutcome outcome = bulkInsert(client, ctx, node, entity, className, vectorAttr, rows);
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
        String className = capitalize(ctx.entityName());

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsRead = 0L;

        long wireStart = System.nanoTime();
        for (int i = 0; i < ctx.targets().size(); i++) {
            RegistryEntry entry = ctx.targets().get(i);
            String id = uuidOf(entry.physicalId() != null ? entry.physicalId() : entry.logicalId());
            try {
                long start = System.nanoTime();
                JsonNode resp = client.get()
                        .uri("/v1/objects/{c}/{id}", className, id)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .timeout(Duration.ofSeconds(15))
                        .block();
                long sampleNs = System.nanoTime() - start;
                samples[i] = sampleNs;
                totalDbTimeNs += sampleNs;
                if (resp != null && resp.hasNonNull("id")) rowsRead++;
            } catch (Exception ex) {
                log.warn("Weaviate read failed {}/{}: {}", className, id, ex.getMessage());
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
        String className = capitalize(ctx.entityName());

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsAffected = 0L;

        long wireStart = System.nanoTime();
        for (int i = 0; i < ctx.targets().size(); i++) {
            RegistryEntry entry = ctx.targets().get(i);
            String id = uuidOf(entry.physicalId() != null ? entry.physicalId() : entry.logicalId());
            try {
                long start = System.nanoTime();
                client.delete()
                        .uri("/v1/objects/{c}/{id}", className, id)
                        .retrieve()
                        .toBodilessEntity()
                        .timeout(Duration.ofSeconds(15))
                        .block();
                long sampleNs = System.nanoTime() - start;
                samples[i] = sampleNs;
                totalDbTimeNs += sampleNs;
                rowsAffected++;
            } catch (Exception ex) {
                log.warn("Weaviate delete failed {}/{}: {}", className, id, ex.getMessage());
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

    private EntityOutcome bulkInsert(WebClient client,
                                     InsertContext ctx,
                                     CascadeNode node,
                                     LogicalEntity entity,
                                     String className,
                                     LogicalAttribute vectorAttr,
                                     List<GeneratedRow> rows) {
        EntityOutcome outcome = new EntityOutcome();
        int batchSize = effectiveBatchSize(ctx);
        int totalBatches = Math.max(1, (int) Math.ceil((double) rows.size() / batchSize));
        int batchIndex = 0;

        for (int from = 0; from < rows.size(); from += batchSize) {
            int to = Math.min(from + batchSize, rows.size());
            List<GeneratedRow> slice = rows.subList(from, to);
            List<Map<String, Object>> objects = new ArrayList<>(slice.size());
            for (GeneratedRow row : slice) {
                Map<String, Object> obj = new LinkedHashMap<>();
                obj.put("class", className);
                obj.put("id", uuidOf(row.logicalId()));
                obj.put("properties", propertiesOf(entity, row, vectorAttr));
                if (vectorAttr != null) {
                    Object v = row.get(vectorAttr.name());
                    if (v instanceof float[] arr) {
                        List<Float> vec = new ArrayList<>(arr.length);
                        for (float f : arr) vec.add(f);
                        obj.put("vector", vec);
                    }
                }
                objects.add(obj);
            }
            try {
                long start = System.nanoTime();
                client.post()
                        .uri("/v1/batch/objects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("objects", objects))
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .timeout(Duration.ofSeconds(60))
                        .block();
                outcome.dbTimeNs += System.nanoTime() - start;
                outcome.rowsAffected += slice.size();
                slice.forEach(r -> outcome.recordedIds.add(new RecordedId(node.entityName(), r.logicalId(), r.logicalId())));
            } catch (Exception ex) {
                log.warn("Weaviate batch insert failed on {} batch {}: {}", className, batchIndex, ex.getMessage());
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

    private Map<String, Object> propertiesOf(LogicalEntity entity, GeneratedRow row, LogicalAttribute vectorAttr) {
        Map<String, Object> props = new LinkedHashMap<>();
        String vectorName = vectorAttr != null ? vectorAttr.name() : null;
        for (Map.Entry<String, Object> entry : row.values().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(vectorName)) continue;
            Object value = entry.getValue();
            if (value instanceof java.time.Instant ins) {
                props.put(entry.getKey(), ins.toString());
            } else if (value instanceof java.time.LocalDate ld) {
                props.put(entry.getKey(), ld.toString() + "T00:00:00Z");
            } else {
                props.put(entry.getKey(), value);
            }
        }
        return props;
    }

    private String uuidOf(String logicalId) {
        try {
            return UUID.fromString(logicalId).toString();
        } catch (IllegalArgumentException ex) {
            return UUID.nameUUIDFromBytes(logicalId.getBytes()).toString();
        }
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    private WebClient clientFor(String host, int port) {
        return WebClient.builder()
                .baseUrl("http://" + host + ":" + port)
                .build();
    }

    private int effectiveBatchSize(InsertContext ctx) {
        return switch (ctx.mode()) {
            case SINGLE -> 1;
            case BATCH -> Math.max(1, ctx.batchSize());
            case BULK -> Math.max(1, ctx.batchSize() > 0 ? ctx.batchSize() : 100);
        };
    }

    private static final class EntityOutcome {
        long dbTimeNs;
        long rowsAffected;
        List<RecordedId> recordedIds = new ArrayList<>();
    }
}
