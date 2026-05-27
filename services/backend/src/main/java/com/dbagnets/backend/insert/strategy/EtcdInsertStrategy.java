package com.dbagnets.backend.insert.strategy;

import com.dbagnets.backend.docker.DockerService;
import com.dbagnets.backend.insert.datagen.GeneratedRecord;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Uses {@code etcdctl} to put key-value pairs (JSON-serialized record).
 * SINGLE/BATCH/BULK all loop {@code put} commands inside a here-doc; etcdctl
 * does not support a literal multi-write API outside of the txn DSL, so we
 * approximate batches by chaining commands in a shell.
 */
public class EtcdInsertStrategy implements DatabaseInsertStrategy {

    private final ObjectMapper mapper;

    public EtcdInsertStrategy(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public InsertOutcome insert(DockerService docker, InsertContext context) {
        String script = buildShellScript(context);
        long start = System.nanoTime();
        try {
            String output = docker.execWithStdin(context.containerId(), script, "sh");
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

    String buildShellScript(InsertContext ctx) {
        StringBuilder sb = new StringBuilder(ctx.records().size() * 96);
        sb.append("set -e\n");
        sb.append("export ETCDCTL_API=3\n");
        String prefix = ctx.entityName().toLowerCase() + "/";
        for (GeneratedRecord r : ctx.records()) {
            String key = prefix + UUID.randomUUID();
            String value = singleQuoteShell(toJson(r));
            sb.append("etcdctl put '").append(key).append("' ").append(value).append("\n");
        }
        return sb.toString();
    }

    private String toJson(GeneratedRecord r) {
        Map<String, Object> values = new LinkedHashMap<>();
        r.values().forEach((k, v) -> values.put(k, ValueFormatter.normalizeForJson(v)));
        return ValueFormatter.jsonLiteral(values, mapper);
    }

    private String singleQuoteShell(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
