package com.dbagnets.backend.insert.cascade;

import com.dbagnets.backend.insert.schema.AttributeConstraints;
import com.dbagnets.backend.insert.schema.LogicalAttribute;
import com.dbagnets.backend.insert.schema.LogicalEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FkColumnHeuristicsTest {

    @Test
    void snakeCaseFkColumn() {
        LogicalEntity child = entity("Order", List.of(attr("id"), attr("user_id"), attr("total")));
        assertEquals("user_id", FkColumnHeuristics.findFkColumn(child, "User"));
    }

    @Test
    void camelCaseFkColumn() {
        LogicalEntity child = entity("Order", List.of(attr("id"), attr("userId"), attr("total")));
        assertEquals("userId", FkColumnHeuristics.findFkColumn(child, "User"));
    }

    @Test
    void containsParentNameWithIdSuffixFallback() {
        LogicalEntity child = entity("Order", List.of(attr("id"), attr("buyer_user_id"), attr("total")));
        assertEquals("buyer_user_id", FkColumnHeuristics.findFkColumn(child, "User"));
    }

    @Test
    void noMatchReturnsNull() {
        LogicalEntity child = entity("Order", List.of(attr("id"), attr("total")));
        assertNull(FkColumnHeuristics.findFkColumn(child, "User"));
    }

    private static LogicalEntity entity(String name, List<LogicalAttribute> attrs) {
        return new LogicalEntity(name, "desc", attrs);
    }

    private static LogicalAttribute attr(String name) {
        return new LogicalAttribute(name, "varchar",
            new AttributeConstraints(false, false, true, false), null);
    }
}
