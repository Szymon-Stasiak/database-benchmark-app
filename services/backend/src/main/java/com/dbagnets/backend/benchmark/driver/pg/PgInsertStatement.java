package com.dbagnets.backend.benchmark.driver.pg;

import com.dbagnets.backend.benchmark.schema.LogicalAttribute;
import com.dbagnets.backend.benchmark.schema.LogicalEntity;

import java.util.List;
import java.util.stream.Collectors;

public record PgInsertStatement(
        String tableName,
        List<LogicalAttribute> orderedColumns,
        String singleRowSql,
        boolean withConflictClause
) {

    public static PgInsertStatement of(LogicalEntity entity) {
        return of(entity, true);
    }

    public static PgInsertStatement of(LogicalEntity entity, boolean withConflictClause) {
        String table = quote(entity.name().toLowerCase());
        List<LogicalAttribute> cols = entity.attributes();
        String colList = cols.stream()
                .map(a -> quote(a.name().toLowerCase()))
                .collect(Collectors.joining(", "));
        String placeholders = cols.stream().map(a -> "?").collect(Collectors.joining(", "));
        String conflictSuffix = withConflictClause ? " ON CONFLICT DO NOTHING" : "";
        String sql = "INSERT INTO " + table + " (" + colList + ") VALUES (" + placeholders + ")" + conflictSuffix;
        return new PgInsertStatement(table, cols, sql, withConflictClause);
    }

    public String multiRowSql(int rows) {
        String singleGroup = "(" + orderedColumns.stream().map(a -> "?").collect(Collectors.joining(", ")) + ")";
        String groups = String.join(", ", java.util.Collections.nCopies(rows, singleGroup));
        String colList = orderedColumns.stream()
                .map(a -> quote(a.name().toLowerCase()))
                .collect(Collectors.joining(", "));
        String conflictSuffix = withConflictClause ? " ON CONFLICT DO NOTHING" : "";
        return "INSERT INTO " + tableName + " (" + colList + ") VALUES " + groups + conflictSuffix;
    }

    public String explainAnalyzeSql() {
        return "EXPLAIN (ANALYZE, FORMAT JSON, BUFFERS) " + singleRowSql;
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
