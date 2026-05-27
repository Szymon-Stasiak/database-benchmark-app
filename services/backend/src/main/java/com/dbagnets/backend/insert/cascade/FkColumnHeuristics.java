package com.dbagnets.backend.insert.cascade;

import com.dbagnets.backend.insert.schema.LogicalAttribute;
import com.dbagnets.backend.insert.schema.LogicalEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Infers which attribute on a child entity holds the foreign key for a given parent.
 *
 * <p>Conventions tried in order:
 * <ol>
 *   <li>{@code <parent>_id} (snake_case)</li>
 *   <li>{@code <parent>Id} (camelCase)</li>
 *   <li>any attribute whose name contains the parent entity name and ends with {@code id}/{@code _id}</li>
 * </ol>
 *
 * <p>If nothing matches we return null; the orchestrator surfaces this as a {@code cascade_warning}
 * so the user can manually map the column or accept that the FK is left null.
 */
public final class FkColumnHeuristics {

    private FkColumnHeuristics() {}

    public static String findFkColumn(LogicalEntity child, String parentEntityName) {
        if (child == null || parentEntityName == null) return null;
        String parent = parentEntityName.toLowerCase(Locale.ROOT);
        List<LogicalAttribute> attrs = child.attributesOrEmpty();

        String snake = parent + "_id";
        String camel = parent + "id";
        for (LogicalAttribute a : attrs) {
            String n = a.name() == null ? "" : a.name().toLowerCase(Locale.ROOT);
            if (n.equals(snake) || n.equals(camel)) return a.name();
        }
        for (LogicalAttribute a : attrs) {
            String n = a.name() == null ? "" : a.name().toLowerCase(Locale.ROOT);
            if (n.contains(parent) && (n.endsWith("_id") || n.endsWith("id"))) return a.name();
        }
        return null;
    }

    /** Build a child→parent FK column map for one cascade plan. Returned map is mutable. */
    public static Map<String, String> mapForChild(LogicalEntity child, List<String> parentNames) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String parent : parentNames) {
            String column = findFkColumn(child, parent);
            if (column != null) out.put(parent, column);
        }
        return out;
    }
}
