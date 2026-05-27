package com.dbagnets.backend.insert.strategy;

import com.dbagnets.backend.docker.DockerService;
import com.dbagnets.backend.insert.datagen.GeneratedRecord;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ArangoInsertStrategy implements DatabaseInsertStrategy {

    private final ObjectMapper mapper;

    public ArangoInsertStrategy(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public InsertOutcome insert(DockerService docker, InsertContext context) {
        String script = buildScript(context);
        long start = System.nanoTime();
        try {
            // arangosh ignores stdin unless --javascript.execute-string is used; pipe via shell
            String output = docker.execWithStdin(context.containerId(), script,
                "arangosh", "--server.password", "root", "--server.database", "_system",
                "--quiet", "--javascript.execute", "/dev/stdin");
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            if (output != null && (output.contains("ArangoError") || output.toLowerCase().contains("error:"))) {
                return InsertOutcome.failure(SqlInsertStrategy.truncate(output, 1000), durationMs);
            }
            return InsertOutcome.success(context.records().size(), durationMs);
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            return InsertOutcome.failure("Exec failed: " + e.getMessage(), durationMs);
        }
    }

    String buildScript(InsertContext ctx) {
        String collection = ctx.entityName();
        StringBuilder sb = new StringBuilder();
        sb.append("var c = db._collection('").append(collection).append("');\n");
        sb.append("if (!c) c = db._create('").append(collection).append("');\n");

        switch (ctx.mode()) {
            case SINGLE -> {
                for (GeneratedRecord r : ctx.records()) {
                    sb.append("c.insert(").append(toJson(r)).append(");\n");
                }
            }
            case BATCH -> {
                int batchSize = ctx.effectiveBatchSize();
                for (int i = 0; i < ctx.records().size(); i += batchSize) {
                    int end = Math.min(i + batchSize, ctx.records().size());
                    sb.append("c.insert(").append(toJsonArray(ctx.records().subList(i, end))).append(");\n");
                }
            }
            case BULK -> sb.append("c.insert(").append(toJsonArray(ctx.records())).append(");\n");
        }
        return sb.toString();
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
