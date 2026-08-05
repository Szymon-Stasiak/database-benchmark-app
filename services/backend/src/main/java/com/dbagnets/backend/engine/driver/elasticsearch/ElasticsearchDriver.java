package com.dbagnets.backend.engine.driver.elasticsearch;

import com.dbagnets.backend.engine.cascade.CascadeNode;
import com.dbagnets.backend.engine.datagen.GeneratedRow;
import com.dbagnets.backend.engine.driver.BatchSizes;
import com.dbagnets.backend.engine.driver.BulkInsertLoop;
import com.dbagnets.backend.engine.driver.DeleteContext;
import com.dbagnets.backend.engine.driver.DriverValues;
import com.dbagnets.backend.engine.driver.EngineDriver;
import com.dbagnets.backend.engine.driver.EntityOutcome;
import com.dbagnets.backend.engine.driver.HttpClients;
import com.dbagnets.backend.engine.driver.InsertContext;
import com.dbagnets.backend.engine.driver.InsertOuterLoop;
import com.dbagnets.backend.engine.driver.PerTargetLoop;
import com.dbagnets.backend.engine.driver.ReadContext;
import com.dbagnets.backend.engine.timing.TimedOperation;
import com.dbagnets.backend.domain.DatabaseEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
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
    public TimedOperation insert(InsertContext ctx) throws Exception {
        WebClient client = clientFor(ctx.hostAddress(), ctx.hostPort());
        return InsertOuterLoop.run(ctx, (node, rows) -> {
            String index = node.entityName().toLowerCase();
            return bulkInsert(client, ctx, node, index, rows);
        });
    }

    @Override
    public TimedOperation read(ReadContext ctx) {
        WebClient client = clientFor(ctx.hostAddress(), ctx.hostPort());
        String index = ctx.entityName().toLowerCase();
        return PerTargetLoop.run(ctx.targets(), "ES read", e -> index + "/" + e.physicalId(), entry -> {
            JsonNode resp = client.get().uri("/{index}/_doc/{id}", index, entry.physicalId()).retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofSeconds(15)).block();
            return resp != null && resp.path("found").asBoolean(false) ? 1 : 0;
        });
    }

    @Override
    public TimedOperation delete(DeleteContext ctx) {
        WebClient client = clientFor(ctx.hostAddress(), ctx.hostPort());
        String index = ctx.entityName().toLowerCase();
        return PerTargetLoop.run(ctx.targets(), "ES delete", e -> index + "/" + e.physicalId(), entry -> {
            JsonNode resp = client.delete().uri("/{index}/_doc/{id}", index, entry.physicalId()).retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofSeconds(15)).block();
            return resp != null && "deleted".equalsIgnoreCase(resp.path("result").asText("")) ? 1 : 0;
        });
    }

    private EntityOutcome bulkInsert(WebClient client, InsertContext ctx, CascadeNode node, String index, List<GeneratedRow> rows) throws Exception {
        BulkInsertLoop.Config config = new BulkInsertLoop.Config(
                BatchSizes.effective(ctx, 1_000),
                engine(),
                false,
                "ES bulk insert failed on {} batch {}: {}",
                null,
                index);
        return BulkInsertLoop.run(ctx, node, rows, config, (slice, batchIndex, totalBatches) -> {
            StringBuilder ndjson = new StringBuilder(slice.size() * 256);
            for (GeneratedRow row : slice) {
                Map<String, Object> action = Map.of("index", Map.of("_index", index, "_id", String.valueOf(row.logicalId())));
                ndjson.append(objectMapper.writeValueAsString(action)).append('\n');
                ndjson.append(objectMapper.writeValueAsString(DriverValues.rowToMap(row))).append('\n');
            }
            client.post().uri("/_bulk").contentType(MediaType.parseMediaType("application/x-ndjson")).bodyValue(ndjson.toString()).retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofSeconds(60)).block();
            return slice.size();
        });
    }

    private WebClient clientFor(String host, int port) {
        return HttpClients.large(host, port);
    }
}
