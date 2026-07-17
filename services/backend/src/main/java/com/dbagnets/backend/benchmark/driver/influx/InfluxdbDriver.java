package com.dbagnets.backend.benchmark.driver.influx;

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
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
            EntityOutcome outcome = writePoints(client, ctx, node, entity, rows);
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
        String measurement = ctx.entityName().toLowerCase();

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsRead = 0L;

        long wireStart = System.nanoTime();
        for (int i = 0; i < ctx.targets().size(); i++) {
            RegistryEntry entry = ctx.targets().get(i);
            String flux = "from(bucket:\"" + BUCKET + "\") |> range(start: -10y) "
                    + "|> filter(fn:(r) => r._measurement == \"" + measurement + "\" and r.id == \"" + entry.physicalId() + "\") "
                    + "|> limit(n:1)";
            try {
                long start = System.nanoTime();
                String body = client.post()
                        .uri(uri -> uri.path("/api/v2/query").queryParam("org", ORG).build())
                        .contentType(MediaType.parseMediaType("application/vnd.flux"))
                        .accept(MediaType.parseMediaType("application/csv"))
                        .bodyValue(flux)
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(Duration.ofSeconds(15))
                        .block();
                long sampleNs = System.nanoTime() - start;
                samples[i] = sampleNs;
                totalDbTimeNs += sampleNs;
                if (body != null && body.lines().anyMatch(l -> l.contains(measurement))) rowsRead++;
            } catch (Exception ex) {
                log.warn("Influx read failed {}/{}: {}", measurement, entry.physicalId(), ex.getMessage());
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
        String measurement = ctx.entityName().toLowerCase();

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsAffected = 0L;

        long wireStart = System.nanoTime();
        for (int i = 0; i < ctx.targets().size(); i++) {
            RegistryEntry entry = ctx.targets().get(i);
            String predicate = "_measurement=\"" + measurement + "\" AND id=\"" + entry.physicalId() + "\"";
            try {
                long start = System.nanoTime();
                client.post()
                        .uri(uri -> uri.path("/api/v2/delete")
                                .queryParam("org", ORG)
                                .queryParam("bucket", BUCKET)
                                .build())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{\"start\":\"1970-01-01T00:00:00Z\",\"stop\":\"2100-01-01T00:00:00Z\",\"predicate\":\"" + predicate + "\"}")
                        .retrieve()
                        .toBodilessEntity()
                        .timeout(Duration.ofSeconds(15))
                        .block();
                long sampleNs = System.nanoTime() - start;
                samples[i] = sampleNs;
                totalDbTimeNs += sampleNs;
                rowsAffected++;
            } catch (Exception ex) {
                log.warn("Influx delete failed {}/{}: {}", measurement, entry.physicalId(), ex.getMessage());
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

    private EntityOutcome writePoints(WebClient client,
                                      InsertContext ctx,
                                      CascadeNode node,
                                      LogicalEntity entity,
                                      List<GeneratedRow> rows) {
        EntityOutcome outcome = new EntityOutcome();
        String measurement = node.entityName().toLowerCase();
        int batchSize = effectiveBatchSize(ctx);
        int totalBatches = Math.max(1, (int) Math.ceil((double) rows.size() / batchSize));
        int batchIndex = 0;

        long baseNanos = Instant.now().toEpochMilli() * 1_000_000L;
        for (int from = 0; from < rows.size(); from += batchSize) {
            int to = Math.min(from + batchSize, rows.size());
            List<GeneratedRow> slice = rows.subList(from, to);
            StringBuilder lp = new StringBuilder(slice.size() * 64);
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
            try {
                long start = System.nanoTime();
                client.post()
                        .uri(uri -> uri.path("/api/v2/write")
                                .queryParam("org", ORG)
                                .queryParam("bucket", BUCKET)
                                .queryParam("precision", "ns")
                                .build())
                        .contentType(MediaType.parseMediaType("text/plain"))
                        .bodyValue(lp.toString())
                        .retrieve()
                        .toBodilessEntity()
                        .timeout(Duration.ofSeconds(60))
                        .block();
                outcome.dbTimeNs += System.nanoTime() - start;
                outcome.rowsAffected += slice.size();
                slice.forEach(r -> outcome.recordedIds.add(new RecordedId(node.entityName(), r.logicalId(), r.logicalId())));
            } catch (Exception ex) {
                log.warn("Influx write failed on {} batch {}: {}", measurement, batchIndex, ex.getMessage());
            }
            batchIndex++;
            ctx.progress().onBatch(node.entityName(), batchIndex, totalBatches, to, rows.size());
        }
        return outcome;
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
        return WebClient.builder()
                .baseUrl("http://" + host + ":" + port)
                .defaultHeader("Authorization", "Token " + TOKEN)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    private int effectiveBatchSize(InsertContext ctx) {
        return switch (ctx.mode()) {
            case SINGLE -> 1;
            case BATCH -> Math.max(1, ctx.batchSize());
            case BULK -> Math.max(1, ctx.batchSize() > 0 ? ctx.batchSize() : 5_000);
        };
    }

    private static final class EntityOutcome {
        long dbTimeNs;
        long rowsAffected;
        List<RecordedId> recordedIds = new ArrayList<>();
    }
}
