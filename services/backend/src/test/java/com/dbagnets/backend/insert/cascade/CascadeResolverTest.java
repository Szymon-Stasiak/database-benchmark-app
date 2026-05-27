package com.dbagnets.backend.insert.cascade;

import com.dbagnets.backend.insert.schema.AttributeConstraints;
import com.dbagnets.backend.insert.schema.LogicalAttribute;
import com.dbagnets.backend.insert.schema.LogicalEntity;
import com.dbagnets.backend.insert.schema.LogicalRelationship;
import com.dbagnets.backend.insert.schema.LogicalSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CascadeResolverTest {

    @Test
    void linearChainOrdersParentsBeforeChildren() {
        LogicalSchema schema = chainSchema();
        // With default 1:N ratio of 5: 1000 Orders → ceil(1000/5)=200 Users → ceil(200/5)=40 Addresses
        CascadePlan plan = CascadeResolver.resolve(
            schema, List.of("Order"), List.of(), Map.of("Order", 1000)
        );
        assertEquals(List.of("Address", "User", "Order"),
            plan.orderedEntities().stream().map(EntityNode::name).toList());
        assertEquals(1000, plan.nodeFor("Order").recordCount());
        assertEquals(200, plan.nodeFor("User").recordCount());
        assertEquals(40, plan.nodeFor("Address").recordCount());
    }

    @Test
    void overridesOnDifferentEdgesCascade() {
        LogicalSchema schema = chainSchema();
        // Override Order→User=5 (default) and User→Address=2 explicitly
        CascadePlan plan = CascadeResolver.resolve(
            schema, List.of("Order"),
            List.of(new EdgeRatioOverride("User", "Address", 2.0)),
            Map.of("Order", 1000)
        );
        assertEquals(200, plan.nodeFor("User").recordCount());
        assertEquals(100, plan.nodeFor("Address").recordCount(), "200/2 = 100 addresses");
    }

    @Test
    void leafOnlyIsKeptAsIs() {
        LogicalSchema schema = chainSchema();
        CascadePlan plan = CascadeResolver.resolve(
            schema, List.of("Address"), List.of(), Map.of("Address", 17)
        );
        assertEquals(1, plan.orderedEntities().size());
        assertEquals("Address", plan.orderedEntities().get(0).name());
        assertEquals(17, plan.nodeFor("Address").recordCount());
    }

    @Test
    void userOverrideRatioChangesParentCount() {
        LogicalSchema schema = chainSchema();
        CascadePlan plan = CascadeResolver.resolve(
            schema, List.of("Order"),
            List.of(new EdgeRatioOverride("Order", "User", 10.0)),
            Map.of("Order", 1000)
        );
        assertEquals(100, plan.nodeFor("User").recordCount(), "ratio 10 → 1000/10 = 100 users");
    }

    @Test
    void manyToManyEdgeIsSkippedFromCascade() {
        LogicalSchema schema = new LogicalSchema(
            "idea", 1, List.of(),
            List.of(
                entity("Student"),
                entity("Course")
            ),
            List.of(new LogicalRelationship("enrolment", "Student", "Course", "M:N", null)),
            List.of()
        );
        CascadePlan plan = CascadeResolver.resolve(
            schema, List.of("Course"), List.of(), Map.of("Course", 50)
        );
        assertEquals(1, plan.orderedEntities().size(), "M:N edge does not pull Student in");
        assertTrue(plan.contains("Course"));
        assertFalse(plan.contains("Student"));
    }

    @Test
    void missingLeafEntityThrows() {
        LogicalSchema schema = chainSchema();
        assertThrows(IllegalArgumentException.class, () ->
            CascadeResolver.resolve(schema, List.of("Missing"), List.of(), Map.of("Missing", 1)));
    }

    /* ====================================================================== */
    /* Fixtures                                                                */
    /* ====================================================================== */

    private static LogicalSchema chainSchema() {
        // Address (1) ← User (N), User (1) ← Order (N)
        return new LogicalSchema(
            "idea", 3, List.of("Address", "User", "Order"),
            List.of(entity("Address"), entity("User"), entity("Order")),
            List.of(
                new LogicalRelationship("user_address", "Address", "User", "1:N", null),
                new LogicalRelationship("order_user", "User", "Order", "1:N", null)
            ),
            List.of()
        );
    }

    private static LogicalEntity entity(String name) {
        return new LogicalEntity(name, "desc",
            List.of(new LogicalAttribute("id", "uuid",
                new AttributeConstraints(true, true, false, true), null)));
    }
}
