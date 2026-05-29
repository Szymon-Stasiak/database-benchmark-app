package com.dbagnets.backend.insert.strategy.jdbc;

import com.dbagnets.backend.insert.strategy.InsertContext;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
public class PostgresJdbcStrategy extends JdbcInsertStrategy {

    @Override
    protected String jdbcUrl(InsertContext ctx) {
        return "jdbc:postgresql://" + ctx.host() + ":" + ctx.hostPort() + "/benchmark";
    }

    @Override
    protected String username(InsertContext ctx) {
        return "postgres";
    }

    /** Must match {@code POSTGRES_PASSWORD} set in {@code BenchmarkService.getDefaultEnvironment}. */
    @Override
    protected String password(InsertContext ctx) {
        return "benchmark";
    }

    /**
     * Postgres-specific enrichment. Two passes, both keyed on the DOMAIN type name:
     *
     * <ol>
     *   <li>Query {@code pg_enum} — labels for each ENUM type used by any column.</li>
     *   <li>Query {@code pg_constraint} — the CHECK clause text for every DOMAIN type, parsed by
     *       {@link CheckConstraintParser} into a {@link ConstraintHint}. This is what stops the
     *       binder from sending a random {@code 64479.0814} into a {@code star_rating} column
     *       whose CHECK requires {@code VALUE BETWEEN 1.0 AND 5.0}.</li>
     * </ol>
     */
    @Override
    protected Map<String, ColumnMeta> enrichColumnsWithEnums(
        Connection conn, Map<String, ColumnMeta> columns
    ) throws Exception {
        Map<String, List<String>> enumLabelsByType = loadEnumLabels(conn, columns);
        Map<String, ConstraintHint> hintsByType = loadDomainCheckHints(conn, columns);
        // Table-level CHECKs are keyed by COLUMN name (lower-case), not domain type name.
        Map<String, ConstraintHint> hintsByColumn = loadTableCheckHints(conn, columns);

        Map<String, ColumnMeta> out = new LinkedHashMap<>(columns.size());
        for (var entry : columns.entrySet()) {
            ColumnMeta col = entry.getValue();
            String tn = col.typeName().toLowerCase(Locale.ROOT);
            List<String> labels = enumLabelsByType.get(tn);
            if (labels != null) col = col.withEnumValues(labels);
            // Column-level (table-level) hint wins over domain-level hint — it's tighter.
            ConstraintHint hint = hintsByColumn.get(entry.getKey());
            if (hint == null) hint = hintsByType.get(tn);
            if (hint != null) col = col.withConstraintHint(hint);
            out.put(entry.getKey(), col);
        }
        return out;
    }

    /**
     * Look up CHECK constraints declared at the TABLE level (not on a DOMAIN). For each constraint
     * that targets a single column, rewrite the expression so the column reference becomes the
     * {@code VALUE} placeholder our generic parser understands, then turn that into a
     * {@link ConstraintHint}. Examples this catches in real LLM-generated schemas:
     *
     * <ul>
     *   <li>{@code CHECK (duration_minutes > 0)} → {@code NumericRange(1, 1001)} for that column</li>
     *   <li>{@code CHECK (price BETWEEN 0 AND 1000)} → {@code NumericRange(0, 1000)}</li>
     *   <li>{@code CHECK (status = ANY(ARRAY['A','B']))} → {@code AllowedValues}</li>
     * </ul>
     *
     * Multi-column checks (e.g. {@code start_date < end_date}) are skipped — too much shape
     * variation to satisfy generically.
     */
    private static Map<String, ConstraintHint> loadTableCheckHints(
        Connection conn, Map<String, ColumnMeta> columns
    ) throws Exception {
        Map<String, ConstraintHint> result = new LinkedHashMap<>();
        // contype='c', conrelid != 0 → table-level (excludes domain checks which use contypid).
        // a.attname gives the referenced column name for single-column checks. Multi-column checks
        // produce multiple rows and we deliberately drop them by GROUP BY HAVING.
        String sql =
            "SELECT a.attname AS col, pg_get_constraintdef(c.oid) AS def " +
            "FROM pg_constraint c " +
            "JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey) " +
            "WHERE c.contype = 'c' AND c.conrelid IN (" +
            "  SELECT oid FROM pg_class WHERE relname = ANY(?))";
        List<String> classNames = new ArrayList<>();
        for (ColumnMeta col : columns.values()) {
            // The table name isn't carried on each column — we capture only the column names and
            // let PG's catalog do the rest. We pass the universe of relevant table names by
            // querying all classes whose attnames intersect with what we have (cheap one-shot).
        }
        // Build the array of column names we care about so we can filter out cross-column checks
        // that mention extra columns we don't recognise.
        java.util.Set<String> myColumns = new java.util.HashSet<>(columns.keySet());

        // Just pull every CHECK in the database — at our scale this is tens of rows max.
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT c.oid, a.attname, pg_get_constraintdef(c.oid) " +
            "FROM pg_constraint c " +
            "JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey) " +
            "WHERE c.contype = 'c' AND c.conrelid != 0")) {
            try (ResultSet rs = ps.executeQuery()) {
                // Group by constraint OID — single-column checks have one row; multi-column have many.
                Map<Long, List<String>> colsByOid = new LinkedHashMap<>();
                Map<Long, String> defByOid = new LinkedHashMap<>();
                while (rs.next()) {
                    long oid = rs.getLong(1);
                    String col = rs.getString(2);
                    String def = rs.getString(3);
                    colsByOid.computeIfAbsent(oid, k -> new ArrayList<>()).add(col);
                    defByOid.putIfAbsent(oid, def);
                }
                for (var e : colsByOid.entrySet()) {
                    List<String> cols = e.getValue();
                    if (cols.size() != 1) continue; // skip multi-column checks
                    String columnName = cols.get(0).toLowerCase(Locale.ROOT);
                    if (!myColumns.contains(columnName)) continue;
                    if (result.containsKey(columnName)) continue;
                    // Substitute the column name with the VALUE keyword so our generic parser
                    // (originally written for DOMAIN expressions) understands it.
                    String def = defByOid.get(e.getKey());
                    String normalised = def.replaceAll("\\b" + java.util.regex.Pattern.quote(cols.get(0)) + "\\b", "VALUE");
                    ConstraintHint hint = CheckConstraintParser.parse(normalised);
                    if (hint != null) {
                        result.put(columnName, hint);
                        log.debug("Parsed table CHECK for column {}: {} → {}", columnName, def, hint);
                    }
                }
            }
        }
        return result;
    }

    private static Map<String, List<String>> loadEnumLabels(
        Connection conn, Map<String, ColumnMeta> columns
    ) throws Exception {
        Map<String, List<String>> result = new HashMap<>();
        String sql = "SELECT enumlabel FROM pg_enum e " +
                     "JOIN pg_type t ON e.enumtypid = t.oid WHERE t.typname = ? ORDER BY e.enumsortorder";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ColumnMeta col : columns.values()) {
                String tn = col.typeName().toLowerCase(Locale.ROOT);
                if (result.containsKey(tn)) continue;
                ps.setString(1, tn);
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> labels = new ArrayList<>();
                    while (rs.next()) labels.add(rs.getString(1));
                    if (!labels.isEmpty()) result.put(tn, labels);
                }
            }
        }
        return result;
    }

    private static Map<String, ConstraintHint> loadDomainCheckHints(
        Connection conn, Map<String, ColumnMeta> columns
    ) throws Exception {
        Map<String, ConstraintHint> result = new HashMap<>();
        // Pull the canonical CHECK definition for every DOMAIN — single query, one row per
        // constraint (a domain can in theory have multiple checks; we keep the first that parses).
        String sql = "SELECT t.typname, pg_get_constraintdef(c.oid) " +
                     "FROM pg_constraint c " +
                     "JOIN pg_type t ON c.contypid = t.oid " +
                     "WHERE c.contype = 'c' AND t.typname = ANY(?)";
        List<String> typeNames = columns.values().stream()
            .map(c -> c.typeName().toLowerCase(Locale.ROOT))
            .distinct()
            .toList();
        if (typeNames.isEmpty()) return result;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setArray(1, conn.createArrayOf("text", typeNames.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String typeName = rs.getString(1).toLowerCase(Locale.ROOT);
                    String checkDef = rs.getString(2);
                    if (result.containsKey(typeName)) continue;
                    ConstraintHint hint = CheckConstraintParser.parse(checkDef);
                    if (hint != null) {
                        result.put(typeName, hint);
                        log.debug("Parsed CHECK for domain {}: {} → {}", typeName, checkDef, hint);
                    } else {
                        log.debug("Unrecognised CHECK for domain {}: {}", typeName, checkDef);
                    }
                }
            }
        }
        return result;
    }
}
