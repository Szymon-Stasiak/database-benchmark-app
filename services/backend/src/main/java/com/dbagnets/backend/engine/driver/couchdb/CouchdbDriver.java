package com.dbagnets.backend.engine.driver.couchdb;

import com.dbagnets.backend.engine.cascade.CascadeNode;
import com.dbagnets.backend.engine.datagen.GeneratedRow;
import com.dbagnets.backend.engine.driver.BatchSizes;
import com.dbagnets.backend.engine.driver.BulkInsertLoop;
import com.dbagnets.backend.engine.driver.DeleteContext;
import com.dbagnets.backend.engine.driver.DocBuilders;
import com.dbagnets.backend.engine.driver.EngineDriver;
import com.dbagnets.backend.engine.driver.EntityOutcome;
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
public class CouchdbDriver implements EngineDriver {

    private static final String AUTH = "Basic " + Base64.getEncoder().encodeToString("admin:benchmark".getBytes(StandardCharsets.UTF_8));

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.COUCHDB;
    }

    @Override
    public TimedOperation insert(InsertContext ctx) throws Exception {
        WebClient client = clientFor(ctx.hostAddress(), ctx.hostPort());
        return InsertOuterLoop.run(ctx, (node, rows) -> {
            String db = node.entityName().toLowerCase();
            ensureDb(client, db);
            LogicalEntity entity = ctx.schema().requireEntity(node.entityName());
            return bulkInsert(client, ctx, node, entity, db, rows);
        });
    }

    @Override
    public TimedOperation read(ReadContext ctx) {
        WebClient client = clientFor(ctx.hostAddress(), ctx.hostPort());
        String db = ctx.entityName().toLowerCase();
        return PerTargetLoop.run(ctx.targets(), "CouchDB read", e -> db + "/" + e.physicalId(), entry -> {
            JsonNode resp = client.get().uri("/{db}/{id}", db, entry.physicalId()).retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofSeconds(15)).block();
            return resp != null && resp.has("_id") ? 1 : 0;
        });
    }

    @Override
    public TimedOperation delete(DeleteContext ctx) {
        WebClient client = clientFor(ctx.hostAddress(), ctx.hostPort());
        String db = ctx.entityName().toLowerCase();
        return PerTargetLoop.run(ctx.targets(), "CouchDB delete", e -> db + "/" + e.physicalId(), entry -> {
            JsonNode head = client.get().uri("/{db}/{id}", db, entry.physicalId()).retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofSeconds(15)).block();
            String rev = head != null ? head.path("_rev").asText("") : "";
            JsonNode resp = client.delete().uri(uri -> uri.path("/{db}/{id}").queryParam("rev", rev).build(db, entry.physicalId())).retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofSeconds(15)).block();
            return resp != null && resp.path("ok").asBoolean(false) ? 1 : 0;
        });
    }

    private void ensureDb(WebClient client, String db) {
        try {
            client.put().uri("/{db}", db).retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofSeconds(15)).onErrorResume(ex -> reactor.core.publisher.Mono.empty()).block();
        } catch (Exception ignore) {
        }
    }

    private EntityOutcome bulkInsert(WebClient client, InsertContext ctx, CascadeNode node, LogicalEntity entity, String db, List<GeneratedRow> rows) throws Exception {
        BulkInsertLoop.Config config = new BulkInsertLoop.Config(
                BatchSizes.effective(ctx, 1_000),
                engine(),
                false,
                "CouchDB bulk insert failed on {} batch {}: {}",
                null,
                db);
        return BulkInsertLoop.run(ctx, node, rows, config, (slice, batchIndex, totalBatches) -> {
            List<Map<String, Object>> docs = slice.stream().map(row -> DocBuilders.withPkAsString("_id", entity, row)).toList();
            client.post().uri("/{db}/_bulk_docs", db).contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("docs", docs)).retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofSeconds(60)).block();
            return slice.size();
        });
    }

    private WebClient clientFor(String host, int port) {
        return HttpClients.withAuthHeader(host, port, AUTH);
    }
}
