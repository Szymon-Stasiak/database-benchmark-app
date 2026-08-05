package com.dbagnets.backend.engine.driver.arango;

import com.dbagnets.backend.engine.datagen.GeneratedRow;
import com.dbagnets.backend.engine.driver.BatchSizes;
import com.dbagnets.backend.engine.driver.BulkInsertLoop;
import com.dbagnets.backend.engine.driver.DeleteContext;
import com.dbagnets.backend.engine.driver.DocBuilders;
import com.dbagnets.backend.engine.driver.EngineDriver;
import com.dbagnets.backend.engine.driver.HttpClients;
import com.dbagnets.backend.engine.driver.InsertContext;
import com.dbagnets.backend.engine.driver.InsertOuterLoop;
import com.dbagnets.backend.engine.driver.PerTargetLoop;
import com.dbagnets.backend.engine.driver.ReadContext;
import com.dbagnets.backend.engine.schema.LogicalEntity;
import com.dbagnets.backend.engine.timing.TimedOperation;
import com.dbagnets.backend.domain.DatabaseEngine;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ArangoDriver implements EngineDriver {

    private static final String AUTH = "Basic " + Base64.getEncoder().encodeToString("root:root".getBytes(StandardCharsets.UTF_8));

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.ARANGODB;
    }

    @Override
    public TimedOperation insert(InsertContext ctx) throws Exception {
        WebClient client = clientFor(ctx.hostAddress(), ctx.hostPort());
        return InsertOuterLoop.run(ctx, (node, rows) -> {
            String collection = node.entityName().toLowerCase();
            ensureCollection(client, collection);
            LogicalEntity entity = ctx.schema().requireEntity(node.entityName());
            return bulkInsert(client, ctx, node, entity, collection, rows);
        });
    }

    @Override
    public TimedOperation read(ReadContext ctx) {
        WebClient client = clientFor(ctx.hostAddress(), ctx.hostPort());
        String collection = ctx.entityName().toLowerCase();
        return PerTargetLoop.run(ctx.targets(), "Arango read", e -> collection + "/" + e.physicalId(), entry -> {
            JsonNode resp = client.get()
                    .uri("/_db/_system/_api/document/{c}/{k}", collection, entry.physicalId())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();
            return resp != null && resp.hasNonNull("_key") ? 1 : 0;
        });
    }

    @Override
    public TimedOperation delete(DeleteContext ctx) {
        WebClient client = clientFor(ctx.hostAddress(), ctx.hostPort());
        String collection = ctx.entityName().toLowerCase();
        return PerTargetLoop.run(ctx.targets(), "Arango delete", e -> collection + "/" + e.physicalId(), entry -> {
            JsonNode resp = client.delete()
                    .uri("/_db/_system/_api/document/{c}/{k}", collection, entry.physicalId())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();
            return resp != null && resp.hasNonNull("_key") ? 1 : 0;
        });
    }

    private void ensureCollection(WebClient client, String collection) {
        try {
            client.post().uri("/_db/_system/_api/collection").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", collection, "type", 2)).retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofSeconds(15)).onErrorResume(ex -> reactor.core.publisher.Mono.empty()).block();
        } catch (Exception ignore) {
        }
    }

    private com.dbagnets.backend.engine.driver.EntityOutcome bulkInsert(WebClient client, InsertContext ctx, com.dbagnets.backend.engine.cascade.CascadeNode node, LogicalEntity entity, String collection, List<GeneratedRow> rows) throws Exception {
        BulkInsertLoop.Config config = new BulkInsertLoop.Config(
                BatchSizes.effective(ctx, 1_000),
                engine(),
                false,
                "Arango bulk insert failed on {} batch {}: {}",
                null,
                collection);
        return BulkInsertLoop.run(ctx, node, rows, config, (slice, batchIndex, totalBatches) -> {
            List<Map<String, Object>> docs = slice.stream().map(row -> DocBuilders.withPkAsString("_key", entity, row)).toList();
            client.post()
                    .uri(uri -> uri.path("/_db/_system/_api/document/{c}").queryParam("overwriteMode", "ignore").build(collection))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(docs)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(60))
                    .block();
            return slice.size();
        });
    }

    private WebClient clientFor(String host, int port) {
        return HttpClients.withAuthHeader(host, port, AUTH);
    }
}
