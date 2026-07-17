package com.dbagnets.backend.benchmark.driver.elasticsearch;

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
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ElasticsearchDriver implements EngineDriver {

    private final ObjectMapper objectMapper;

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.ELASTICSEARCH;
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
            String index = node.entityName().toLowerCase();
            LogicalEntity entity = ctx.schema().requireEntity(node.entityName());
            EntityOutcome outcome = bulkInsert(client, ctx, node, entity, index, rows);
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
        String index = ctx.entityName().toLowerCase();

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsRead = 0L;

        long wireStart = System.nanoTime();
        for (int i = 0; i < ctx.targets().size(); i++) {
            RegistryEntry entry = ctx.targets().get(i);
            try {
                long start = System.nanoTime();
                JsonNode resp = client.get()
                        .uri("/{index}/_doc/{id}", index, entry.physicalId())
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .timeout(Duration.ofSeconds(15))
                        .block();
                long sampleNs = System.nanoTime() - start;
                samples[i] = sampleNs;
                totalDbTimeNs += sampleNs;
                if (resp != null && resp.path("found").asBoolean(false)) rowsRead++;
            } catch (Exception ex) {
                log.warn("ES read failed {}/{}: {}", index, entry.physicalId(), ex.getMessage());
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
        String index = ctx.entityName().toLowerCase();

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsAffected = 0L;

        long wireStart = System.nanoTime();
        for (int i = 0; i < ctx.targets().size(); i++) {
            RegistryEntry entry = ctx.targets().get(i);
            try {
                long start = System.nanoTime();
                JsonNode resp = client.delete()
                        .uri("/{index}/_doc/{id}", index, entry.physicalId())
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .timeout(Duration.ofSeconds(15))
                        .block();
                long sampleNs = System.nanoTime() - start;
                samples[i] = sampleNs;
                totalDbTimeNs += sampleNs;
                if (resp != null && "deleted".equalsIgnoreCase(resp.path("result").asText(""))) rowsAffected++;
            } catch (Exception ex) {
                log.warn("ES delete failed {}/{}: {}", index, entry.physicalId(), ex.getMessage());
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
                                     String index,
                                     List<GeneratedRow> rows) {
        EntityOutcome outcome = new EntityOutcome();
        int batchSize = effectiveBatchSize(ctx);
        int totalBatches = Math.max(1, (int) Math.ceil((double) rows.size() / batchSize));
        int batchIndex = 0;

        for (int from = 0; from < rows.size(); from += batchSize) {
            int to = Math.min(from + batchSize, rows.size());
            List<GeneratedRow> slice = rows.subList(from, to);
            StringBuilder ndjson = new StringBuilder(slice.size() * 256);
            try {
                for (GeneratedRow row : slice) {
                    Map<String, Object> action = Map.of("index", Map.of("_index", index, "_id", String.valueOf(row.logicalId())));
                    ndjson.append(objectMapper.writeValueAsString(action)).append('\n');
                    ndjson.append(objectMapper.writeValueAsString(toSource(entity, row))).append('\n');
                }
                long start = System.nanoTime();
                client.post()
                        .uri("/_bulk")
                        .contentType(MediaType.parseMediaType("application/x-ndjson"))
                        .bodyValue(ndjson.toString())
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .timeout(Duration.ofSeconds(60))
                        .block();
                outcome.dbTimeNs += System.nanoTime() - start;
                outcome.rowsAffected += slice.size();
                slice.forEach(r -> outcome.recordedIds.add(new RecordedId(node.entityName(), r.logicalId(), r.logicalId())));
            } catch (Exception ex) {
                log.warn("ES bulk insert failed on {} batch {}: {}", index, batchIndex, ex.getMessage());
            }
            batchIndex++;
            ctx.progress().onBatch(node.entityName(), batchIndex, totalBatches, to, rows.size());
        }
        return outcome;
    }

    private Map<String, Object> toSource(LogicalEntity entity, GeneratedRow row) {
        Map<String, Object> source = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.values().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof float[] arr) {
                List<Double> list = new ArrayList<>(arr.length);
                for (float f : arr) list.add((double) f);
                source.put(entry.getKey(), list);
            } else if (value instanceof java.time.Instant ins) {
                source.put(entry.getKey(), ins.toString());
            } else if (value instanceof java.time.LocalDate ld) {
                source.put(entry.getKey(), ld.toString());
            } else {
                source.put(entry.getKey(), value);
            }
        }
        return source;
    }

    private WebClient clientFor(String host, int port) {
        return WebClient.builder()
                .baseUrl("http://" + host + ":" + port)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
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
