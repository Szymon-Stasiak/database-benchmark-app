package com.dbagnets.backend.insert.strategy;

import com.dbagnets.backend.insert.datagen.GeneratedRecord;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class QdrantInsertStrategy extends HttpApiInsertStrategy {

    public QdrantInsertStrategy(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    protected String singleMethod(InsertContext ctx) { return "PUT"; }

    @Override
    protected String bulkMethod(InsertContext ctx) { return "PUT"; }

    @Override
    protected String singleEndpoint(InsertContext ctx) {
        return baseUrl(ctx) + "/collections/" + collection(ctx) + "/points";
    }

    @Override
    protected String bulkEndpoint(InsertContext ctx) {
        return singleEndpoint(ctx);
    }

    @Override
    protected String singleBody(InsertContext ctx, GeneratedRecord record) {
        return ValueFormatter.jsonLiteral(Map.of("points", List.of(pointFor(record))), mapper);
    }

    @Override
    protected String bulkBody(InsertContext ctx, List<GeneratedRecord> records) {
        List<Map<String, Object>> points = new ArrayList<>(records.size());
        for (GeneratedRecord r : records) points.add(pointFor(r));
        return ValueFormatter.jsonLiteral(Map.of("points", points), mapper);
    }

    private Map<String, Object> pointFor(GeneratedRecord r) {
        Map<String, Object> point = new HashMap<>();
        point.put("id", UUID.randomUUID().toString());
        // Qdrant requires a vector; if none is in the schema use a dummy 4-dim vector.
        Object vec = recordAsJson(r).get("vector");
        point.put("vector", vec instanceof double[] arr ? arr : new double[]{0.1, 0.2, 0.3, 0.4});
        point.put("payload", recordAsJson(r));
        return point;
    }

    private String collection(InsertContext ctx) {
        return ctx.entityName();
    }
}
