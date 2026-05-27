package com.dbagnets.backend.insert.strategy;

import com.dbagnets.backend.docker.DockerService;
import com.dbagnets.backend.insert.datagen.GeneratedRecord;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MongoInsertStrategy implements DatabaseInsertStrategy {

    private final ObjectMapper mapper;

    public MongoInsertStrategy(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public InsertOutcome insert(DockerService docker, InsertContext context) {
        String script = buildScript(context);
        long start = System.nanoTime();
        try {
            String output = docker.execWithStdin(context.containerId(), script, "mongosh", "--quiet", "benchmark");
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            if (output != null && (output.contains("MongoServerError") || output.contains("SyntaxError") || output.contains("MongoshInvalidInputError"))) {
                return InsertOutcome.failure(SqlInsertStrategy.truncate(output, 1000), durationMs);
            }
            return InsertOutcome.success(context.records().size(), durationMs);
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            return InsertOutcome.failure("Exec failed: " + e.getMessage(), durationMs);
        }
    }

    String buildScript(InsertContext ctx) {
        // Mongo collection names are case-sensitive — use the entity name verbatim
        // so it matches whatever the script-creator created (e.g. db.createCollection("Cinema")).
        String collection = ctx.entityName();
        return switch (ctx.mode()) {
            case SINGLE -> single(collection, ctx.records());
            case BATCH -> batched(collection, ctx.records(), ctx.effectiveBatchSize());
            case BULK -> bulk(collection, ctx.records());
        };
    }

    private String single(String coll, List<GeneratedRecord> records) {
        StringBuilder sb = new StringBuilder(records.size() * 128);
        for (GeneratedRecord r : records) {
            sb.append("db.").append(coll).append(".insertOne(").append(toJson(r)).append(");\n");
        }
        return sb.toString();
    }

    private String batched(String coll, List<GeneratedRecord> records, int batchSize) {
        StringBuilder sb = new StringBuilder(records.size() * 128);
        for (int i = 0; i < records.size(); i += batchSize) {
            int end = Math.min(i + batchSize, records.size());
            sb.append("db.").append(coll).append(".insertMany(")
              .append(toJsonArray(records.subList(i, end))).append(");\n");
        }
        return sb.toString();
    }

    private String bulk(String coll, List<GeneratedRecord> records) {
        return "db." + coll + ".insertMany(" + toJsonArray(records) + ");\n";
    }

    private String toJson(GeneratedRecord r) {
        Map<String, Object> values = new LinkedHashMap<>();
        r.values().forEach((k, v) -> values.put(k, ValueFormatter.normalizeForJson(v)));
        return ValueFormatter.jsonLiteral(values, mapper);
    }

    private String toJsonArray(List<GeneratedRecord> records) {
        List<Object> list = new ArrayList<>(records.size());
        for (GeneratedRecord r : records) {
            Map<String, Object> values = new LinkedHashMap<>();
            r.values().forEach((k, v) -> values.put(k, ValueFormatter.normalizeForJson(v)));
            list.add(values);
        }
        return ValueFormatter.jsonLiteral(list, mapper);
    }
}
