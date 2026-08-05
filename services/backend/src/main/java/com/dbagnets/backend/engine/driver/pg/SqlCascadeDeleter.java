package com.dbagnets.backend.engine.driver.pg;

import com.dbagnets.backend.engine.driver.CascadeBfsState;
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
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

public final class SqlCascadeDeleter {

    private static final int MAX_DEPTH = 16;

    private SqlCascadeDeleter() {
    }

    public record Dialect(UnaryOperator<String> quote, Binder binder) {
    }

    @FunctionalInterface
    public interface Binder {
        void bind(PreparedStatement stmt, int index, LogicalAttribute attr, Object value) throws SQLException;
    }

    public static int deleteRoot(Connection conn, LogicalEntity entity, LogicalAttribute pk, Object physicalId, Dialect dialect) throws SQLException {
        String sql = "DELETE FROM " + dialect.quote().apply(entity.name().toLowerCase()) + " WHERE " + dialect.quote().apply(pk.name().toLowerCase()) + " = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            dialect.binder().bind(ps, 1, pk, physicalId);
            return ps.executeUpdate();
        }
    }

    public static int deleteRootBatch(Connection conn, LogicalEntity entity, LogicalAttribute pk, List<Object> physicalIds, Dialect dialect) throws SQLException {
        if (physicalIds.isEmpty()) return 0;
        String sql = "DELETE FROM " + dialect.quote().apply(entity.name().toLowerCase()) + " WHERE " + dialect.quote().apply(pk.name().toLowerCase()) + " = ?";
        int affected = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Object id : physicalIds) {
                dialect.binder().bind(ps, 1, pk, id);
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            for (int c : counts) if (c > 0) affected += c;
        }
        return affected;
    }

    public static int deleteRootBulk(Connection conn, LogicalEntity entity, LogicalAttribute pk, List<Object> physicalIds, Dialect dialect) throws SQLException {
        if (physicalIds.isEmpty()) return 0;
        String placeholders = physicalIds.stream().map(x -> "?").collect(Collectors.joining(", "));
        String sql = "DELETE FROM " + dialect.quote().apply(entity.name().toLowerCase()) + " WHERE " + dialect.quote().apply(pk.name().toLowerCase()) + " IN (" + placeholders + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < physicalIds.size(); i++) {
                dialect.binder().bind(ps, i + 1, pk, physicalIds.get(i));
            }
            return ps.executeUpdate();
        }
    }

    public static long readBulk(Connection conn, LogicalEntity entity, LogicalAttribute pk, List<Object> physicalIds, Dialect dialect) throws SQLException {
        if (physicalIds.isEmpty()) return 0L;
        String placeholders = physicalIds.stream().map(x -> "?").collect(Collectors.joining(", "));
        String sql = "SELECT * FROM " + dialect.quote().apply(entity.name().toLowerCase()) + " WHERE " + dialect.quote().apply(pk.name().toLowerCase()) + " IN (" + placeholders + ")";
        long count = 0L;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < physicalIds.size(); i++) {
                dialect.binder().bind(ps, i + 1, pk, physicalIds.get(i));
            }
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) count++;
            }
        }
        return count;
    }

    public static Map<String, List<Object>> cascadeChildrenOf(Connection conn, LogicalSchema schema, String rootEntity, Object rootPhysicalId, Dialect dialect) throws SQLException {
        CascadeBfsState state = new CascadeBfsState(rootEntity, rootPhysicalId, MAX_DEPTH);
        while (state.hasNext()) {
            String cur = state.poll();
            if (!state.visit(cur)) continue;
            List<Object> curIds = state.idsFor(cur);
            if (curIds == null || curIds.isEmpty()) continue;

            for (LogicalRelationship rel : schema.relationships()) {
                if (!rel.parentEntity().equalsIgnoreCase(cur)) continue;
                String childName = rel.childEntity();
                if (childName.equalsIgnoreCase(cur)) continue;
                LogicalEntity child = schema.findEntity(childName).orElse(null);
                if (child == null) continue;
                LogicalAttribute childPk = child.primaryKey().orElse(null);
                if (childPk == null) continue;
                String fkCol = resolveFkColumn(rel, schema);
                if (fkCol == null) continue;
                LogicalAttribute parentPk = schema.findEntity(rel.parentEntity()).flatMap(LogicalEntity::primaryKey).orElse(null);
                if (parentPk == null) continue;

                List<Object> childIds = selectChildIds(conn, child, childPk, fkCol, parentPk, curIds, dialect);
                if (!childIds.isEmpty()) state.addChildren(childName, childIds);
            }
        }

        for (String entityName : state.reversedEntityOrder()) {
            if (entityName.equalsIgnoreCase(rootEntity)) continue;
            List<Object> ids = state.idsFor(entityName);
            if (ids == null || ids.isEmpty()) continue;
            LogicalEntity entity = schema.requireEntity(entityName);
            LogicalAttribute pk = entity.primaryKey().orElse(null);
            if (pk == null) continue;
            deleteByIds(conn, entity, pk, ids, dialect);
        }
        Map<String, List<Object>> result = state.snapshot();
        result.remove(rootEntity);
        return result;
    }

    private static List<Object> selectChildIds(Connection conn, LogicalEntity child, LogicalAttribute childPk, String fkCol, LogicalAttribute parentPk, List<Object> parentIds, Dialect dialect) throws SQLException {
        String sql = "SELECT " + dialect.quote().apply(childPk.name().toLowerCase()) + " FROM " + dialect.quote().apply(child.name().toLowerCase()) + " WHERE " + dialect.quote().apply(fkCol.toLowerCase()) + " = ?";
        List<Object> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Object parentId : parentIds) {
                dialect.binder().bind(ps, 1, parentPk, parentId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Object value = rs.getObject(1);
                        if (value != null) ids.add(value);
                    }
                }
            }
        }
        return ids;
    }

    private static void deleteByIds(Connection conn, LogicalEntity entity, LogicalAttribute pk, List<Object> ids, Dialect dialect) throws SQLException {
        String sql = "DELETE FROM " + dialect.quote().apply(entity.name().toLowerCase()) + " WHERE " + dialect.quote().apply(pk.name().toLowerCase()) + " = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Object id : ids) {
                dialect.binder().bind(ps, 1, pk, id);
                ps.executeUpdate();
            }
        }
    }

    private static String resolveFkColumn(LogicalRelationship rel, LogicalSchema schema) {
        String declared = rel.fkColumnInChild();
        if (declared != null && !declared.isBlank()) return declared;
        LogicalEntity parent = schema.findEntity(rel.parentEntity()).orElse(null);
        if (parent == null) return null;
        LogicalAttribute parentPk = parent.primaryKey().orElse(null);
        if (parentPk == null) return null;
        LogicalEntity child = schema.findEntity(rel.childEntity()).orElse(null);
        if (child == null) return null;
        return child.attributes().stream().anyMatch(a -> a.name().equalsIgnoreCase(parentPk.name())) ? parentPk.name() : null;
    }
}
