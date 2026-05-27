package com.dbagnets.backend.insert.strategy;

import com.dbagnets.backend.docker.DockerService;
import com.dbagnets.backend.insert.datagen.GeneratedRecord;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class RedisInsertStrategy implements DatabaseInsertStrategy {

    private final ObjectMapper mapper;

    public RedisInsertStrategy(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public InsertOutcome insert(DockerService docker, InsertContext context) {
        String script = buildScript(context);
        long start = System.nanoTime();
        try {
            // --pipe consumes RESP, but a series of SET commands works with plain redis-cli too.
            String output = docker.execWithStdin(context.containerId(), script, "redis-cli");
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

    String buildScript(InsertContext ctx) {
        String prefix = ctx.entityName().toLowerCase() + ":";
        return switch (ctx.mode()) {
            case SINGLE -> single(prefix, ctx.records());
            case BATCH -> batched(prefix, ctx.records(), ctx.effectiveBatchSize());
            case BULK -> bulkMset(prefix, ctx.records());
        };
    }

    private String single(String prefix, List<GeneratedRecord> records) {
        StringBuilder sb = new StringBuilder(records.size() * 64);
        for (GeneratedRecord r : records) {
            sb.append("SET ").append(prefix).append(UUID.randomUUID())
              .append(" ").append(quoteForCli(toJson(r))).append("\n");
        }
        return sb.toString();
    }

    private String batched(String prefix, List<GeneratedRecord> records, int batchSize) {
        StringBuilder sb = new StringBuilder(records.size() * 64);
        for (int i = 0; i < records.size(); i += batchSize) {
            int end = Math.min(i + batchSize, records.size());
            sb.append("MSET");
            for (int j = i; j < end; j++) {
                sb.append(" ").append(prefix).append(UUID.randomUUID())
                  .append(" ").append(quoteForCli(toJson(records.get(j))));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String bulkMset(String prefix, List<GeneratedRecord> records) {
        StringBuilder sb = new StringBuilder(records.size() * 64);
        sb.append("MSET");
        for (GeneratedRecord r : records) {
            sb.append(" ").append(prefix).append(UUID.randomUUID())
              .append(" ").append(quoteForCli(toJson(r)));
        }
        sb.append("\n");
        return sb.toString();
    }

    private String toJson(GeneratedRecord r) {
        Map<String, Object> values = new LinkedHashMap<>();
        r.values().forEach((k, v) -> values.put(k, ValueFormatter.normalizeForJson(v)));
        return ValueFormatter.jsonLiteral(values, mapper);
    }

    /** redis-cli understands double-quoted arguments with backslash escapes. */
    private String quoteForCli(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
