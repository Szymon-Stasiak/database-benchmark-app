package com.dbagnets.backend.engine.driver.pg;

import com.dbagnets.backend.engine.scenario.ScenarioSupport;
import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalEntity;
import com.dbagnets.backend.engine.schema.LogicalRelationship;
import com.dbagnets.backend.engine.schema.LogicalSchema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class PgScenarios {

    private PgScenarios() {
    }

    public static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    public static String buildAggregateSql(LogicalSchema schema, String parentEntity, String childEntity) {
        LogicalRelationship rel = ScenarioSupport.findRelationship(schema, parentEntity, childEntity);
        String childTable = quote(childEntity.toLowerCase());
        String fkCol = quote(rel.fkColumnInChild().toLowerCase());
        return "SELECT " + fkCol + " AS group_key, COUNT(*) AS cnt FROM " + childTable + " WHERE " + fkCol + " IS NOT NULL" + " GROUP BY " + fkCol + " ORDER BY " + fkCol;
    }

    public static Map<String, Long> executeAggregate(Connection conn, LogicalSchema schema, String parentEntity, String childEntity) throws SQLException {
        String sql = buildAggregateSql(schema, parentEntity, childEntity);
        Map<String, Long> result = new TreeMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Object key = rs.getObject(1);
                long count = rs.getLong(2);
                if (key != null) result.put(String.valueOf(key), count);
            }
        }
        return result;
    }

    public static String buildRangeSql(LogicalSchema schema, String entityName, String attribute) {
        LogicalEntity entity = schema.requireEntity(entityName);
        LogicalAttribute attr = entity.findAttribute(attribute).orElseThrow(() -> new IllegalArgumentException("Attribute " + attribute + " not found on " + entityName));
        if (!ScenarioSupport.isNumericLike(attr)) {
            throw new IllegalArgumentException("Attribute " + attribute + " on " + entityName + " is not numeric — type " + attr.dataType());
        }
        String table = quote(entity.name().toLowerCase());
        String col = quote(attr.name().toLowerCase());
        return "SELECT COUNT(*) FROM " + table + " WHERE " + col + " BETWEEN ? AND ?";
    }

    public static long executeRangeCount(Connection conn, LogicalSchema schema, String entityName, String attribute, double min, double max) throws SQLException {
        String sql = buildRangeSql(schema, entityName, attribute);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, min);
            ps.setDouble(2, max);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
                return 0L;
            }
        }
    }

    public static List<TraversalLevel> resolveChain(LogicalSchema schema, String startEntity, int depth) {
        List<TraversalLevel> chain = new ArrayList<>();
        String current = startEntity;
        for (int level = 1; level <= depth; level++) {
            final String parent = current;
            LogicalRelationship next = schema.relationships().stream().filter(r -> r.parentEntity().equalsIgnoreCase(parent)).findFirst().orElse(null);
            if (next == null) break;
            chain.add(new TraversalLevel(level, next.parentEntity(), next.childEntity(), next.fkColumnInChild()));
            current = next.childEntity();
        }
        return chain;
    }

    public static String buildTraversalSql(LogicalSchema schema, String startEntity, int depth) {
        LogicalEntity start = schema.requireEntity(startEntity);
        LogicalAttribute startPk = start.primaryKey().orElseThrow(() -> new IllegalStateException(startEntity + " has no primary key"));
        List<TraversalLevel> chain = resolveChain(schema, startEntity, depth);
        if (chain.isEmpty()) {
            return "SELECT CAST(NULL AS TEXT) AS id WHERE FALSE";
        }

        StringBuilder cteBlock = new StringBuilder("WITH walk_0 AS (").append("SELECT ").append(quote(startPk.name().toLowerCase())).append(" AS id ").append("FROM ").append(quote(start.name().toLowerCase())).append(" WHERE ").append(quote(startPk.name().toLowerCase())).append(" = ?").append(")");

        StringBuilder unionBlock = new StringBuilder();
        for (TraversalLevel level : chain) {
            LogicalEntity childEntity = schema.requireEntity(level.childEntity());
            LogicalAttribute childPk = childEntity.primaryKey().orElseThrow(() -> new IllegalStateException(level.childEntity() + " has no primary key"));
            String cteName = "walk_" + level.depth();
            String prevCte = "walk_" + (level.depth() - 1);
            cteBlock.append(", ").append(cteName).append(" AS (").append("SELECT child.").append(quote(childPk.name().toLowerCase())).append(" AS id ").append("FROM ").append(quote(childEntity.name().toLowerCase())).append(" child").append(" JOIN ").append(prevCte).append(" parent ON child.").append(quote(level.fkColumn().toLowerCase())).append(" = parent.id").append(")");
            if (!unionBlock.isEmpty()) unionBlock.append(" UNION ALL ");
            unionBlock.append("SELECT CAST(id AS TEXT) AS reachable_id FROM ").append(cteName);
        }

        return cteBlock + " SELECT reachable_id FROM (" + unionBlock + ") reached ORDER BY reachable_id";
    }

    public static List<String> executeTraversal(Connection conn, LogicalSchema schema, String startEntity, String startLogicalId, int depth) throws SQLException {
        String sql = buildTraversalSql(schema, startEntity, depth);
        LogicalEntity start = schema.requireEntity(startEntity);
        LogicalAttribute pk = start.primaryKey().orElseThrow();
        java.util.Set<String> ids = new java.util.TreeSet<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            PgValueBinder.bind(ps, 1, pk, startLogicalId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    if (id != null) ids.add(id);
                }
            }
        }
        return new ArrayList<>(ids);
    }

    public record TraversalLevel(int depth, String parentEntity, String childEntity, String fkColumn) {
    }
}
