package com.dbagnets.backend.benchmark.driver.mysql;

import com.dbagnets.backend.benchmark.schema.LogicalAttribute;
import com.dbagnets.backend.benchmark.schema.LogicalEntity;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public record MysqlInsertStatement(
        String tableName,
        List<LogicalAttribute> orderedColumns,
        String singleRowSql
) {

    public static MysqlInsertStatement of(LogicalEntity entity) {
        String table = quote(entity.name().toLowerCase());
        List<LogicalAttribute> cols = entity.attributes();
        String colList = cols.stream()
                .map(a -> quote(a.name().toLowerCase()))
                .collect(Collectors.joining(", "));
        String placeholders = cols.stream().map(a -> "?").collect(Collectors.joining(", "));
        String sql = "INSERT IGNORE INTO " + table + " (" + colList + ") VALUES (" + placeholders + ")";
        return new MysqlInsertStatement(table, cols, sql);
    }

    public String multiRowSql(int rows) {
        String singleGroup = "(" + orderedColumns.stream().map(a -> "?").collect(Collectors.joining(", ")) + ")";
        String groups = String.join(", ", Collections.nCopies(rows, singleGroup));
        String colList = orderedColumns.stream()
                .map(a -> quote(a.name().toLowerCase()))
                .collect(Collectors.joining(", "));
        return "INSERT IGNORE INTO " + tableName + " (" + colList + ") VALUES " + groups;
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
