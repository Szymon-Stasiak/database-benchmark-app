package com.dbagnets.backend.engine.cascade;

import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalDataType;
import com.dbagnets.backend.engine.schema.LogicalEntity;
import com.dbagnets.backend.engine.schema.LogicalRelationship;
import com.dbagnets.backend.engine.schema.LogicalSchema;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ForeignKeyResolver {

    private ForeignKeyResolver() {
    }

    public static String resolve(LogicalSchema schema, LogicalRelationship relationship) {
        if (relationship.fkColumnInChild() != null && !relationship.fkColumnInChild().isBlank()) {
            return relationship.fkColumnInChild();
        }
        LogicalEntity child = schema.requireEntity(relationship.childEntity());
        LogicalEntity parent = schema.requireEntity(relationship.parentEntity());

        Optional<LogicalAttribute> match = findFkAttribute(child.attributes(), parent.name());
        if (match.isEmpty()) {
            throw new IllegalStateException(unresolvedMessage(relationship, child, parent));
        }
        return match.get().name();
    }

    private static Optional<LogicalAttribute> findFkAttribute(List<LogicalAttribute> attrs, String parentName) {
        String parentLower = parentName.toLowerCase(Locale.ROOT);
        String snakeParent = toSnakeCase(parentName);
        return attrs.stream()
                .filter(a -> a.dataType() == LogicalDataType.UUID)
                .filter(a -> {
                    String n = a.name().toLowerCase(Locale.ROOT);
                    return n.equals(snakeParent + "_id")
                            || n.equals(parentLower + "id")
                            || n.equals(parentLower + "_id")
                            || n.contains(snakeParent + "_id");
                })
                .findFirst();
    }

    private static String toSnakeCase(String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static String unresolvedMessage(LogicalRelationship rel, LogicalEntity child, LogicalEntity parent) {
        return String.format(
                "Cannot resolve FK column in child entity '%s' for relationship '%s' → parent '%s'. " +
                        "Expected a UUID attribute named like '%s_id' or '%sid'. Add 'fk_column_in_child' " +
                        "to the relationship definition to make this explicit.",
                child.name(), rel.name(), parent.name(),
                toSnakeCase(parent.name()), parent.name().toLowerCase(Locale.ROOT));
    }
}
