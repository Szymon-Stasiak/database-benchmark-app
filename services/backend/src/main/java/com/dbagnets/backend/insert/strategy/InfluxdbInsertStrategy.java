package com.dbagnets.backend.insert.strategy;

import com.dbagnets.backend.insert.datagen.GeneratedRecord;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes line protocol to InfluxDB 2.x {@code /api/v2/write}.
 * SINGLE = one line per request; BATCH = batchSize lines per request; BULK = all in one.
 * Requires a valid token; backend uses the bootstrap token defined for the container.
 */
public class InfluxdbInsertStrategy extends HttpApiInsertStrategy {

    private static final String TOKEN_HEADER = "Token benchmark-token";

    public InfluxdbInsertStrategy(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    protected Map<String, String> headers(InsertContext ctx) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", TOKEN_HEADER);
        h.put("Content-Type", "text/plain; charset=utf-8");
        return h;
    }

    @Override
    protected String singleEndpoint(InsertContext ctx) {
        return baseUrl(ctx) + "/api/v2/write?org=benchmark&bucket=benchmark&precision=s";
    }

    @Override
    protected String bulkEndpoint(InsertContext ctx) {
        return singleEndpoint(ctx);
    }

    @Override
    protected String singleBody(InsertContext ctx, GeneratedRecord record) {
        return lineFor(ctx, record);
    }

    @Override
    protected String bulkBody(InsertContext ctx, List<GeneratedRecord> records) {
        StringBuilder sb = new StringBuilder(records.size() * 64);
        for (GeneratedRecord r : records) sb.append(lineFor(ctx, r)).append("\n");
        return sb.toString();
    }

    private String lineFor(InsertContext ctx, GeneratedRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append(escape(ctx.entityName().toLowerCase())).append(" ");
        boolean first = true;
        for (Map.Entry<String, Object> e : r.values().entrySet()) {
            if (e.getValue() == null) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append(escape(e.getKey())).append("=");
            Object v = e.getValue();
            if (v instanceof Number) sb.append(v);
            else if (v instanceof Boolean b) sb.append(b ? "true" : "false");
            else sb.append("\"").append(v.toString().replace("\"", "\\\"")).append("\"");
        }
        return sb.toString();
    }

    private String escape(String s) {
        return s.replace(" ", "\\ ").replace(",", "\\,").replace("=", "\\=");
    }
}
