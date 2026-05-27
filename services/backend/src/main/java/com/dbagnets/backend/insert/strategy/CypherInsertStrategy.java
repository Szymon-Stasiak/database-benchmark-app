package com.dbagnets.backend.insert.strategy;

import com.dbagnets.backend.docker.DockerService;
import com.dbagnets.backend.insert.datagen.GeneratedRecord;
import com.dbagnets.backend.insert.schema.LogicalAttribute;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public class CypherInsertStrategy implements DatabaseInsertStrategy {

    private final ObjectMapper mapper;
    private final String dbName;

    public CypherInsertStrategy(String dbName, ObjectMapper mapper) {
        this.dbName = dbName;
        this.mapper = mapper;
    }

    @Override
    public InsertOutcome insert(DockerService docker, InsertContext context) {
        String script = buildScript(context);
        long start = System.nanoTime();
        try {
            String output = execute(docker, context.containerId(), script);
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            if (output != null && output.toLowerCase().contains("error")) {
                return InsertOutcome.failure(SqlInsertStrategy.truncate(output, 1000), durationMs);
            }
            return InsertOutcome.success(context.records().size(), durationMs);
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            return InsertOutcome.failure("Exec failed: " + e.getMessage(), durationMs);
        }
    }

    private String execute(DockerService docker, String containerId, String script) {
        if ("memgraph".equals(dbName)) {
            return docker.execWithStdin(containerId, script, "mgconsole");
        }
        return docker.execWithStdin(containerId, script, "cypher-shell", "-u", "neo4j", "-p", "benchmark", "--format", "plain");
    }

    String buildScript(InsertContext ctx) {
        String label = labelName(ctx.entityName());
        return switch (ctx.mode()) {
            case SINGLE -> single(label, ctx);
            case BATCH -> batched(label, ctx, ctx.effectiveBatchSize());
            case BULK -> bulk(label, ctx);
        };
    }

    private String single(String label, InsertContext ctx) {
        StringBuilder sb = new StringBuilder(ctx.records().size() * 128);
        for (GeneratedRecord r : ctx.records()) {
            sb.append("CREATE (:").append(label).append(" ").append(propsLiteral(ctx.attributes(), r)).append(");\n");
        }
        return sb.toString();
    }

    private String batched(String label, InsertContext ctx, int batchSize) {
        StringBuilder sb = new StringBuilder(ctx.records().size() * 128);
        for (int i = 0; i < ctx.records().size(); i += batchSize) {
            int end = Math.min(i + batchSize, ctx.records().size());
            sb.append(unwindBlock(label, ctx, ctx.records().subList(i, end))).append("\n");
        }
        return sb.toString();
    }

    private String bulk(String label, InsertContext ctx) {
        return unwindBlock(label, ctx, ctx.records()) + "\n";
    }

    private String unwindBlock(String label, InsertContext ctx, List<GeneratedRecord> records) {
        List<Map<String, Object>> rows = new ArrayList<>(records.size());
        for (GeneratedRecord r : records) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (LogicalAttribute a : ctx.attributes()) {
                values.put(a.name(), ValueFormatter.normalizeForJson(r.get(a.name())));
            }
            rows.add(values);
        }
        String list = ValueFormatter.jsonLiteral(rows, mapper);
        return "UNWIND " + list + " AS row CREATE (n:" + label + ") SET n = row;";
    }

    private String propsLiteral(List<LogicalAttribute> attributes, GeneratedRecord record) {
        StringJoiner kv = new StringJoiner(", ", "{", "}");
        for (LogicalAttribute a : attributes) {
            Object v = record.get(a.name());
            kv.add(a.name() + ": " + ValueFormatter.cypherLiteral(v, mapper));
        }
        return kv.toString();
    }

    private String labelName(String entityName) {
        if (entityName == null || entityName.isEmpty()) return "Entity";
        char first = Character.toUpperCase(entityName.charAt(0));
        return first + entityName.substring(1).replaceAll("[^A-Za-z0-9_]", "_");
    }
}
