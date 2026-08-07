package com.dbagnets.backend.engine.driver.engines.weaviate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;

import com.dbagnets.backend.domain.DatabaseEngine;
import com.dbagnets.backend.engine.cascade.CascadeNode;
import com.dbagnets.backend.engine.datagen.GeneratedRow;
import com.dbagnets.backend.engine.driver.api.DeleteContext;
import com.dbagnets.backend.engine.driver.api.EngineDriver;
import com.dbagnets.backend.engine.driver.api.EntityOutcome;
import com.dbagnets.backend.engine.driver.api.InsertContext;
import com.dbagnets.backend.engine.driver.api.ReadContext;
import com.dbagnets.backend.engine.driver.support.BatchSizes;
import com.dbagnets.backend.engine.driver.support.BulkInsertLoop;
import com.dbagnets.backend.engine.driver.support.HttpClients;
import com.dbagnets.backend.engine.driver.support.InsertOuterLoop;
import com.dbagnets.backend.engine.driver.support.PerTargetLoop;
import com.dbagnets.backend.engine.driver.support.VectorAttributes;
import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalEntity;
import com.dbagnets.backend.engine.timing.TimedOperation;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class WeaviateDriver implements EngineDriver {

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.WEAVIATE;
    }

    @Override
    public TimedOperation insert(InsertContext ctx) throws Exception {
        WebClient client = clientFor(ctx.hostAddress(), ctx.hostPort());
        return InsertOuterLoop.run(
                ctx,
                (node, rows) -> {
                    LogicalEntity entity = ctx.schema().requireEntity(node.entityName());
                    String className = capitalize(node.entityName());
                    LogicalAttribute vectorAttr = VectorAttributes.find(entity);
                    return bulkInsert(client, ctx, node, className, vectorAttr, rows);
                });
    }

    @Override
    public TimedOperation read(ReadContext ctx) {
        WebClient client = clientFor(ctx.hostAddress(), ctx.hostPort());
        String className = capitalize(ctx.entityName());
        return PerTargetLoop.run(
                ctx.targets(),
                "Weaviate read",
                e ->
                        className
                                + "/"
                                + uuidOf(e.physicalId() != null ? e.physicalId() : e.logicalId()),
                entry -> {
                    String id =
                            uuidOf(
                                    entry.physicalId() != null
                                            ? entry.physicalId()
                                            : entry.logicalId());
                    JsonNode resp =
                            client.get()
                                    .uri("/v1/objects/{c}/{id}", className, id)
                                    .retrieve()
                                    .bodyToMono(JsonNode.class)
                                    .timeout(Duration.ofSeconds(15))
                                    .block();
                    return resp != null && resp.hasNonNull("id") ? 1 : 0;
                });
    }

    @Override
    public TimedOperation delete(DeleteContext ctx) {
        WebClient client = clientFor(ctx.hostAddress(), ctx.hostPort());
        String className = capitalize(ctx.entityName());
        return PerTargetLoop.run(
                ctx.targets(),
                "Weaviate delete",
                e ->
                        className
                                + "/"
                                + uuidOf(e.physicalId() != null ? e.physicalId() : e.logicalId()),
                entry -> {
                    String id =
                            uuidOf(
                                    entry.physicalId() != null
                                            ? entry.physicalId()
                                            : entry.logicalId());
                    client.delete()
                            .uri("/v1/objects/{c}/{id}", className, id)
                            .retrieve()
                            .toBodilessEntity()
                            .timeout(Duration.ofSeconds(15))
                            .block();
                    return 1;
                });
    }

    private EntityOutcome bulkInsert(
            WebClient client,
            InsertContext ctx,
            CascadeNode node,
            String className,
            LogicalAttribute vectorAttr,
            List<GeneratedRow> rows)
            throws Exception {
        BulkInsertLoop.Config config =
                new BulkInsertLoop.Config(
                        BatchSizes.effective(ctx, 100),
                        engine(),
                        false,
                        "Weaviate batch insert failed on {} batch {}: {}",
                        null,
                        className);
        return BulkInsertLoop.run(
                ctx,
                node,
                rows,
                config,
                (slice, batchIndex, totalBatches) -> {
                    List<Map<String, Object>> objects = new ArrayList<>(slice.size());
                    for (GeneratedRow row : slice) {
                        Map<String, Object> obj = new LinkedHashMap<>();
                        obj.put("class", className);
                        obj.put("id", uuidOf(row.logicalId()));
                        obj.put("properties", propertiesOf(row, vectorAttr));
                        if (vectorAttr != null) {
                            Object v = row.get(vectorAttr.name());
                            if (v instanceof float[] arr) {
                                obj.put("vector", VectorAttributes.toList(arr));
                            }
                        }
                        objects.add(obj);
                    }
                    client.post()
                            .uri("/v1/batch/objects")
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(Map.of("objects", objects))
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .timeout(Duration.ofSeconds(60))
                            .block();
                    return slice.size();
                });
    }

    private Map<String, Object> propertiesOf(GeneratedRow row, LogicalAttribute vectorAttr) {
        Map<String, Object> props = new LinkedHashMap<>();
        String vectorName = vectorAttr != null ? vectorAttr.name() : null;
        for (Map.Entry<String, Object> entry : row.values().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(vectorName)) continue;
            Object value = entry.getValue();
            if (value instanceof java.time.Instant ins) {
                props.put(entry.getKey(), ins.toString());
            } else if (value instanceof java.time.LocalDate ld) {
                props.put(entry.getKey(), ld + "T00:00:00Z");
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
        return HttpClients.basic(host, port);
    }
}
