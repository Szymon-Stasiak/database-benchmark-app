package com.dbagnets.backend.insert.strategy;

import com.dbagnets.backend.docker.DockerService;
import com.dbagnets.backend.insert.datagen.GeneratedRecord;
import com.dbagnets.backend.insert.schema.LogicalAttribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.StringJoiner;

/**
 * Generates SQL INSERT statements in three modes:
 * <ul>
 *   <li>SINGLE — one INSERT per record (autocommit by default for most engines).
 *   <li>BATCH — wraps batchSize records in a BEGIN ... COMMIT transaction.
 *   <li>BULK — a single INSERT INTO t (cols) VALUES (rec1),(rec2),...,(recN).
 * </ul>
 * Subclasses only provide the client command (psql, mysql, ...) and any quirks.
 */
public abstract class SqlInsertStrategy implements DatabaseInsertStrategy {

    private static final Logger log = LoggerFactory.getLogger(SqlInsertStrategy.class);

    @Override
    public InsertOutcome insert(DockerService docker, InsertContext context) {
        String sql = buildSql(context);
        long start = System.nanoTime();
        try {
            String output = docker.execWithStdin(context.containerId(), sql, clientCommand(context));
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            String error = detectError(output);
            if (error != null) {
                return InsertOutcome.failure(truncate(error, 1000), durationMs);
            }
            return InsertOutcome.success(context.records().size(), durationMs);
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("SQL insert failed for {}", context.dbName(), e);
            return InsertOutcome.failure("Exec failed: " + e.getMessage(), durationMs);
        }
    }

    String buildSql(InsertContext ctx) {
        return switch (ctx.mode()) {
            case SINGLE -> buildSingle(ctx);
            case BATCH -> buildBatch(ctx);
            case BULK -> buildBulk(ctx);
        };
    }

    private String buildSingle(InsertContext ctx) {
        String header = insertHeader(ctx);
        StringBuilder sb = new StringBuilder(ctx.records().size() * 128);
        for (GeneratedRecord r : ctx.records()) {
            sb.append(header).append(" VALUES (").append(formatRow(ctx.attributes(), r)).append(");\n");
        }
        return sb.toString();
    }

    private String buildBatch(InsertContext ctx) {
        int batchSize = ctx.effectiveBatchSize();
        String header = insertHeader(ctx);
        StringBuilder sb = new StringBuilder(ctx.records().size() * 128);
        List<GeneratedRecord> records = ctx.records();
        for (int i = 0; i < records.size(); i += batchSize) {
            int end = Math.min(i + batchSize, records.size());
            sb.append(beginTransaction()).append("\n");
            for (int j = i; j < end; j++) {
                sb.append(header).append(" VALUES (")
                    .append(formatRow(ctx.attributes(), records.get(j))).append(");\n");
            }
            sb.append(commitTransaction()).append("\n");
        }
        return sb.toString();
    }

    private String buildBulk(InsertContext ctx) {
        String header = insertHeader(ctx);
        StringJoiner values = new StringJoiner("),\n  (", "  (", ")");
        for (GeneratedRecord r : ctx.records()) {
            values.add(formatRow(ctx.attributes(), r));
        }
        return header + " VALUES\n" + values + ";\n";
    }

    private String insertHeader(InsertContext ctx) {
        String table = tableName(ctx.entityName());
        StringJoiner cols = new StringJoiner(", ", "(", ")");
        // Columns: use raw attribute names (no quoting). Most schemas use snake_case identifiers
        // that are valid unquoted; quoting forces case-sensitive lookup which usually misses.
        for (LogicalAttribute a : ctx.attributes()) cols.add(a.name());
        return "INSERT INTO " + table + " " + cols;
    }

    private String formatRow(List<LogicalAttribute> attributes, GeneratedRecord record) {
        StringJoiner values = new StringJoiner(", ");
        for (LogicalAttribute a : attributes) {
            values.add(ValueFormatter.sqlLiteral(record.get(a.name())));
        }
        return values.toString();
    }

    /**
     * Use the entity name as it appears in the LogicalSchema (no lowercase, no quoting).
     * PostgreSQL/MySQL fold unquoted identifiers to a canonical case, so {@code Cinema} matches
     * {@code CREATE TABLE Cinema}. Quoting would force case-sensitive lookup and mismatch the
     * script-creator output (NamingConsistencyChecker preserves the entity name verbatim).
     */
    protected String tableName(String entityName) {
        return entityName;
    }

    protected String quoteIdent(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    protected String beginTransaction() {
        return "BEGIN;";
    }

    protected String commitTransaction() {
        return "COMMIT;";
    }

    protected abstract String[] clientCommand(InsertContext ctx);

    /** Return non-null error message if output indicates failure. */
    protected String detectError(String output) {
        if (output == null) return null;
        String lower = output.toLowerCase();
        if (lower.contains("error:") || lower.contains("syntax error") || lower.contains("fatal:")) {
            return output.lines().filter(l -> l.toLowerCase().contains("error")).findFirst().orElse(output);
        }
        return null;
    }

    static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
