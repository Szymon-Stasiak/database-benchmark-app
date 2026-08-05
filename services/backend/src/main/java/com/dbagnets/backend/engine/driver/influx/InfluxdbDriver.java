package com.dbagnets.backend.engine.driver.influx;

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
import com.dbagnets.backend.engine.timing.TimedOperation;
import com.dbagnets.backend.domain.DatabaseEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Component
public class InfluxdbDriver implements EngineDriver {

    private static final String ORG = "benchmark";
    private static final String BUCKET = "benchmark";
    private static final String TOKEN = "benchmark-admin-token";

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.INFLUXDB;
    }

    @Override
    public TimedOperation insert(InsertContext ctx) throws Exception {
        WebClient client = clientFor(ctx.hostAddress(), ctx.hostPort());
        return InsertOuterLoop.run(ctx, (node, rows) -> writePoints(client, ctx, node, rows));
    }

    @Override
    public TimedOperation read(ReadContext ctx) {
        WebClient client = clientFor(ctx.hostAddress(), ctx.hostPort());
        String measurement = ctx.entityName().toLowerCase();
        return PerTargetLoop.run(ctx.targets(), "Influx read", e -> measurement + "/" + e.physicalId(), entry -> {
            String flux = "from(bucket:\"" + BUCKET + "\") |> range(start: -10y) " + "|> filter(fn:(r) => r._measurement == \"" + measurement + "\" and r.id == \"" + entry.physicalId() + "\") " + "|> limit(n:1)";
            String body = client.post().uri(uri -> uri.path("/api/v2/query").queryParam("org", ORG).build()).contentType(MediaType.parseMediaType("application/vnd.flux")).accept(MediaType.parseMediaType("application/csv")).bodyValue(flux).retrieve().bodyToMono(String.class).timeout(Duration.ofSeconds(15)).block();
            return body != null && body.lines().anyMatch(l -> l.contains(measurement)) ? 1 : 0;
        });
    }

    @Override
    public TimedOperation delete(DeleteContext ctx) {
        WebClient client = clientFor(ctx.hostAddress(), ctx.hostPort());
        String measurement = ctx.entityName().toLowerCase();
        return PerTargetLoop.run(ctx.targets(), "Influx delete", e -> measurement + "/" + e.physicalId(), entry -> {
            String predicate = "_measurement=\"" + measurement + "\" AND id=\"" + entry.physicalId() + "\"";
            client.post().uri(uri -> uri.path("/api/v2/delete").queryParam("org", ORG).queryParam("bucket", BUCKET).build()).contentType(MediaType.APPLICATION_JSON).bodyValue("{\"start\":\"1970-01-01T00:00:00Z\",\"stop\":\"2100-01-01T00:00:00Z\",\"predicate\":\"" + predicate + "\"}").retrieve().toBodilessEntity().timeout(Duration.ofSeconds(15)).block();
            return 1;
        });
    }

    private EntityOutcome writePoints(WebClient client, InsertContext ctx, CascadeNode node, List<GeneratedRow> rows) throws Exception {
        String measurement = node.entityName().toLowerCase();
        long baseNanos = Instant.now().toEpochMilli() * 1_000_000L;
        BulkInsertLoop.Config config = new BulkInsertLoop.Config(
                BatchSizes.effective(ctx, 5_000),
                engine(),
                false,
                "Influx write failed on {} batch {}: {}",
                null,
                measurement);
        return BulkInsertLoop.run(ctx, node, rows, config, (slice, batchIndex, totalBatches) -> {
            StringBuilder lp = new StringBuilder(slice.size() * 64);
            int from = batchIndex * config.batchSize;
            for (int idx = 0; idx < slice.size(); idx++) {
                GeneratedRow row = slice.get(idx);
                lp.append(measurement);
                lp.append(",id=").append(escapeTag(row.logicalId()));
                lp.append(' ');
                boolean firstField = true;
                for (var e : row.values().entrySet()) {
                    Object v = e.getValue();
                    if (v == null || v instanceof float[]) continue;
                    if (!firstField) lp.append(',');
                    lp.append(e.getKey()).append('=').append(formatField(v));
                    firstField = false;
                }
                if (firstField) {
                    lp.append("_v=1i");
                }
                lp.append(' ').append(baseNanos + (long) (from + idx));
                lp.append('\n');
            }
            client.post().uri(uri -> uri.path("/api/v2/write").queryParam("org", ORG).queryParam("bucket", BUCKET).queryParam("precision", "ns").build()).contentType(MediaType.parseMediaType("text/plain")).bodyValue(lp.toString()).retrieve().toBodilessEntity().timeout(Duration.ofSeconds(60)).block();
            return slice.size();
        });
    }

    private String formatField(Object value) {
        if (value instanceof Number n) {
            if (value instanceof Integer || value instanceof Long) return n.longValue() + "i";
            return Double.toString(n.doubleValue());
        }
        if (value instanceof Boolean b) return b ? "true" : "false";
        return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String escapeTag(String value) {
        return value.replace(" ", "\\ ").replace(",", "\\,").replace("=", "\\=");
    }

    private WebClient clientFor(String host, int port) {
        return HttpClients.largeWithAuthHeader(host, port, "Token " + TOKEN);
    }
}
