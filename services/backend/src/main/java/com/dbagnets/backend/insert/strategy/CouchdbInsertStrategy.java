package com.dbagnets.backend.insert.strategy;

import com.dbagnets.backend.insert.datagen.GeneratedRecord;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CouchdbInsertStrategy extends HttpApiInsertStrategy {

    private static final String AUTH = "Basic " + Base64.getEncoder().encodeToString("admin:benchmark".getBytes());

    public CouchdbInsertStrategy(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    protected Map<String, String> headers(InsertContext ctx) {
        Map<String, String> h = new HashMap<>(super.headers(ctx));
        h.put("Authorization", AUTH);
        return h;
    }

    @Override
    protected String singleEndpoint(InsertContext ctx) {
        return baseUrl(ctx) + "/" + database(ctx);
    }

    @Override
    protected String bulkEndpoint(InsertContext ctx) {
        return baseUrl(ctx) + "/" + database(ctx) + "/_bulk_docs";
    }

    @Override
    protected String singleBody(InsertContext ctx, GeneratedRecord record) {
        return ValueFormatter.jsonLiteral(recordAsJson(record), mapper);
    }

    @Override
    protected String bulkBody(InsertContext ctx, List<GeneratedRecord> records) {
        List<Map<String, Object>> docs = records.stream().map(this::recordAsJson).collect(Collectors.toList());
        return ValueFormatter.jsonLiteral(Map.of("docs", docs), mapper);
    }

    private String database(InsertContext ctx) {
        // CouchDB requires lowercase database names. If the entity uses CamelCase,
        // the script-creator must have lowercased it too.
        return ctx.entityName().toLowerCase();
    }
}
