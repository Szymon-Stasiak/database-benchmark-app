package com.dbagnets.backend.insert.strategy;

import com.dbagnets.backend.insert.datagen.GeneratedRecord;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WeaviateInsertStrategy extends HttpApiInsertStrategy {

    public WeaviateInsertStrategy(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    protected String singleEndpoint(InsertContext ctx) {
        return baseUrl(ctx) + "/v1/objects";
    }

    @Override
    protected String bulkEndpoint(InsertContext ctx) {
        return baseUrl(ctx) + "/v1/batch/objects";
    }

    @Override
    protected String singleBody(InsertContext ctx, GeneratedRecord record) {
        return ValueFormatter.jsonLiteral(objectFor(ctx, record), mapper);
    }

    @Override
    protected String bulkBody(InsertContext ctx, List<GeneratedRecord> records) {
        List<Map<String, Object>> objects = new ArrayList<>(records.size());
        for (GeneratedRecord r : records) objects.add(objectFor(ctx, r));
        return ValueFormatter.jsonLiteral(Map.of("objects", objects), mapper);
    }

    private Map<String, Object> objectFor(InsertContext ctx, GeneratedRecord r) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("class", className(ctx));
        object.put("properties", recordAsJson(r));
        return object;
    }

    private String className(InsertContext ctx) {
        String e = ctx.entityName();
        if (e == null || e.isEmpty()) return "Entity";
        return Character.toUpperCase(e.charAt(0)) + e.substring(1);
    }
}
