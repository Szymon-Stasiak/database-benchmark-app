package com.dbagnets.backend.insert.strategy;

import com.dbagnets.backend.docker.DockerService;
import com.dbagnets.backend.insert.datagen.GeneratedRecord;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for HTTP API insert strategies. Reaches the database container via
 * the host port that the orchestrator already exposed. Concrete subclasses
 * override URL, method, body and headers per database.
 *
 * Three modes:
 * <ul>
 *   <li>SINGLE — N requests via {@link #singleEndpoint(InsertContext)} + {@link #singleBody(InsertContext, GeneratedRecord)}.
 *   <li>BATCH — N/batchSize requests via {@link #bulkEndpoint(InsertContext)} + {@link #bulkBody(InsertContext, List)}.
 *   <li>BULK  — one request via {@link #bulkEndpoint(InsertContext)} + {@link #bulkBody(InsertContext, List)}.
 * </ul>
 */
public abstract class HttpApiInsertStrategy implements DatabaseInsertStrategy {

    protected static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    protected final ObjectMapper mapper;

    protected HttpApiInsertStrategy(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public InsertOutcome insert(DockerService docker, InsertContext context) {
        if (context.hostPort() == null) {
            return InsertOutcome.failure("Host port not assigned for HTTP-based insert", 0);
        }
        long start = System.nanoTime();
        try {
            switch (context.mode()) {
                case SINGLE -> runSingle(context);
                case BATCH -> runBatched(context, context.effectiveBatchSize());
                case BULK -> runBulk(context);
            }
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            return InsertOutcome.success(context.records().size(), durationMs);
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            return InsertOutcome.failure("HTTP insert failed: " + e.getMessage(), durationMs);
        }
    }

    private void runSingle(InsertContext ctx) throws Exception {
        URI uri = URI.create(singleEndpoint(ctx));
        Map<String, String> headers = headers(ctx);
        for (GeneratedRecord r : ctx.records()) {
            send(uri, singleMethod(ctx), headers, singleBody(ctx, r));
        }
    }

    private void runBatched(InsertContext ctx, int batchSize) throws Exception {
        URI uri = URI.create(bulkEndpoint(ctx));
        Map<String, String> headers = headers(ctx);
        for (int i = 0; i < ctx.records().size(); i += batchSize) {
            int end = Math.min(i + batchSize, ctx.records().size());
            send(uri, bulkMethod(ctx), headers, bulkBody(ctx, ctx.records().subList(i, end)));
        }
    }

    private void runBulk(InsertContext ctx) throws Exception {
        URI uri = URI.create(bulkEndpoint(ctx));
        send(uri, bulkMethod(ctx), headers(ctx), bulkBody(ctx, ctx.records()));
    }

    private void send(URI uri, String method, Map<String, String> headers, String body) throws Exception {
        BodyPublisher publisher = body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body);
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).method(method, publisher).timeout(Duration.ofSeconds(60));
        headers.forEach(b::header);
        HttpResponse<String> res = HTTP.send(b.build(), BodyHandlers.ofString());
        if (res.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + res.statusCode() + ": " + SqlInsertStrategy.truncate(res.body(), 500));
        }
    }

    protected String baseUrl(InsertContext ctx) {
        return "http://localhost:" + ctx.hostPort();
    }

    protected String singleMethod(InsertContext ctx) { return "POST"; }
    protected String bulkMethod(InsertContext ctx) { return "POST"; }

    protected Map<String, String> headers(InsertContext ctx) {
        return Map.of("Content-Type", "application/json");
    }

    protected abstract String singleEndpoint(InsertContext ctx);
    protected abstract String bulkEndpoint(InsertContext ctx);
    protected abstract String singleBody(InsertContext ctx, GeneratedRecord record);
    protected abstract String bulkBody(InsertContext ctx, List<GeneratedRecord> records);

    protected Map<String, Object> recordAsJson(GeneratedRecord r) {
        Map<String, Object> values = new LinkedHashMap<>();
        r.values().forEach((k, v) -> values.put(k, ValueFormatter.normalizeForJson(v)));
        return values;
    }
}
