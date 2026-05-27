package com.dbagnets.backend.insert.cascade;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecordCountCalculatorTest {

    @Test
    void linearChainPropagatesUpward() {
        // Order(leaf, 1000) -> User(parent, 1000/5 = 200) -> Address(grandparent, 200/2 = 100)
        Map<String, Integer> counts = RecordCountCalculator.compute(
            List.of("Address", "User", "Order"),
            List.of(
                new CascadeEdge("Order", "User", Cardinality.ONE_TO_MANY, 5.0),
                new CascadeEdge("User", "Address", Cardinality.ONE_TO_MANY, 2.0)
            ),
            Map.of("Order", 1000)
        );
        assertEquals(1000, counts.get("order"));
        assertEquals(200, counts.get("user"));
        assertEquals(100, counts.get("address"));
    }

    @Test
    void ceilRounding() {
        Map<String, Integer> counts = RecordCountCalculator.compute(
            List.of("User", "Order"),
            List.of(new CascadeEdge("Order", "User", Cardinality.ONE_TO_MANY, 3.0)),
            Map.of("Order", 10)
        );
        assertEquals(4, counts.get("user"), "ceil(10/3) = 4");
    }

    @Test
    void multipleChildrenOfSameParentAccumulate() {
        // Both Order and Review reference User; User needs ceil(1000/10) + ceil(500/5) = 100 + 100
        Map<String, Integer> counts = RecordCountCalculator.compute(
            List.of("User", "Order", "Review"),
            List.of(
                new CascadeEdge("Order", "User", Cardinality.ONE_TO_MANY, 10.0),
                new CascadeEdge("Review", "User", Cardinality.ONE_TO_MANY, 5.0)
            ),
            Map.of("Order", 1000, "Review", 500)
        );
        assertEquals(200, counts.get("user"));
    }

    @Test
    void ancestorWithoutDemandPathStillGetsAtLeastOne() {
        Map<String, Integer> counts = RecordCountCalculator.compute(
            List.of("DanglingAncestor", "Order"),
            List.of(),
            Map.of("Order", 50)
        );
        assertEquals(50, counts.get("order"));
        assertEquals(1, counts.get("danglingancestor"), "ensures ancestor isn't skipped");
    }

    @Test
    void explicitParentCountOverridesPropagation() {
        // User wants exactly 50 Users even though Order/5 = 200 would normally suggest more.
        // Children's demand is 200; explicit is 50 → MAX wins (200) to keep referential integrity.
        Map<String, Integer> counts = RecordCountCalculator.compute(
            List.of("User", "Order"),
            List.of(new CascadeEdge("Order", "User", Cardinality.ONE_TO_MANY, 5.0)),
            Map.of("Order", 1000, "User", 50)
        );
        assertEquals(1000, counts.get("order"));
        assertEquals(200, counts.get("user"),
            "explicit user count (50) bumped to 200 because children need at least that many");
    }

    @Test
    void explicitParentCountHonoredWhenChildrenNeedFewer() {
        // 1000 Users explicit; Orders=100, ratio 5 → only need 20 Users → keep explicit 1000.
        Map<String, Integer> counts = RecordCountCalculator.compute(
            List.of("User", "Order"),
            List.of(new CascadeEdge("Order", "User", Cardinality.ONE_TO_MANY, 5.0)),
            Map.of("Order", 100, "User", 1000)
        );
        assertEquals(1000, counts.get("user"),
            "explicit user count wins when children's demand is lower");
    }
}
