package com.dbagnets.backend.engine.cascade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.dbagnets.backend.engine.schema.AttributeConstraints;
import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalDataType;
import com.dbagnets.backend.engine.schema.LogicalEntity;
import com.dbagnets.backend.engine.schema.LogicalRelationship;
import com.dbagnets.backend.engine.schema.LogicalSchema;
import com.dbagnets.backend.engine.schema.RelationshipCardinality;

class CascadePlannerTest {

    private final CascadePlanner planner = new CascadePlanner();

    @Test
    void singleLeafProducesPlanWithJustThatEntity() {
        LogicalSchema schema = schemaOf(List.of(entity("Genre", pk("genre_id"))), List.of());
        CascadePlan plan = planner.plan(schema, List.of(new LeafChoice("Genre", 10)));

        assertThat(plan.nodesInInsertOrder()).hasSize(1);
        assertThat(plan.nodesInInsertOrder().get(0).entityName()).isEqualTo("Genre");
        assertThat(plan.nodesInInsertOrder().get(0).recordCount()).isEqualTo(10);
    }

    @Test
    void parentsArePlannedBeforeChildrenAndCountsPropagate() {
        LogicalSchema schema =
                schemaOf(
                        List.of(
                                entity("Movie", pk("movie_id")),
                                entity("Review", pk("review_id"), fk("movie_id"))),
                        List.of(rel("Movie", "Review", RelationshipCardinality.ONE_TO_MANY)));

        CascadePlan plan = planner.plan(schema, List.of(new LeafChoice("Review", 100)));

        assertThat(plan.nodesInInsertOrder().stream().map(CascadeNode::entityName))
                .containsExactly("Movie", "Review");
        assertThat(plan.byEntity().get("Movie").recordCount()).isEqualTo(20);
        assertThat(plan.byEntity().get("Review").recordCount()).isEqualTo(100);
    }

    @Test
    void manyToManyRelationshipsAreSkipped() {
        LogicalSchema schema =
                schemaOf(
                        List.of(entity("Movie", pk("movie_id")), entity("Actor", pk("actor_id"))),
                        List.of(rel("Movie", "Actor", RelationshipCardinality.MANY_TO_MANY)));

        CascadePlan plan = planner.plan(schema, List.of(new LeafChoice("Actor", 10)));

        assertThat(plan.nodesInInsertOrder()).hasSize(1);
        assertThat(plan.nodesInInsertOrder().get(0).entityName()).isEqualTo("Actor");
    }

    @Test
    void ratioOverrideAffectsPropagatedParentCount() {
        LogicalSchema schema =
                schemaOf(
                        List.of(
                                entity("Movie", pk("movie_id")),
                                entity("Review", pk("review_id"), fk("movie_id"))),
                        List.of(rel("Movie", "Review", RelationshipCardinality.ONE_TO_MANY)));

        CascadePlan plan =
                planner.plan(
                        schema,
                        List.of(new LeafChoice("Review", 100)),
                        Map.of("Movie_Review", 10.0));

        assertThat(plan.byEntity().get("Movie").recordCount()).isEqualTo(10);
    }

    @Test
    void intermediateNodeUsedAsLeafGetsCorrectCountBeforePropagatingToAncestor() {
        // Chain: Parent → Child → Grandchild
        // Leaves: Child=100 and Grandchild=2000
        // Child's final count = max(100, ceil(2000/10)) = 200
        // Parent must be derived from Child=200, not from the initial leaf value of 100
        LogicalSchema schema =
                schemaOf(
                        List.of(
                                entity("Parent", pk("parent_id")),
                                entity("Child", pk("child_id"), fk("parent_id")),
                                entity("Grandchild", pk("grandchild_id"), fk("child_id"))),
                        List.of(
                                rel("Parent", "Child", RelationshipCardinality.ONE_TO_MANY),
                                rel("Child", "Grandchild", RelationshipCardinality.ONE_TO_MANY)));

        CascadePlan plan =
                planner.plan(
                        schema,
                        List.of(new LeafChoice("Child", 100), new LeafChoice("Grandchild", 2000)),
                        Map.of("Parent_Child", 10.0, "Child_Grandchild", 10.0));

        assertThat(plan.byEntity().get("Child").recordCount()).isEqualTo(200);
        assertThat(plan.byEntity().get("Parent").recordCount()).isEqualTo(20);
    }

    @Test
    void longerPathDominatesAncestorCountInFanInTopology() {
        // Z → X → L1 (short path, weak)
        // X → Y → L2 (longer path, strong)
        // X from L1: ceil(100/100)=1; X from Y: ceil(100/5)=20 → X=20
        // Z must be derived from X=20, not from the stale X=1 seen via the short path
        LogicalSchema schema =
                schemaOf(
                        List.of(
                                entity("Z", pk("z_id")),
                                entity("X", pk("x_id"), fk("z_id")),
                                entity("Y", pk("y_id"), fk("x_id")),
                                entity("L1", pk("l1_id"), fk("x_id")),
                                entity("L2", pk("l2_id"), fk("y_id"))),
                        List.of(
                                rel("Z", "X", RelationshipCardinality.ONE_TO_MANY),
                                rel("X", "L1", RelationshipCardinality.ONE_TO_MANY),
                                rel("X", "Y", RelationshipCardinality.ONE_TO_MANY),
                                rel("Y", "L2", RelationshipCardinality.ONE_TO_MANY)));

        CascadePlan plan =
                planner.plan(
                        schema,
                        List.of(new LeafChoice("L1", 100), new LeafChoice("L2", 1000)),
                        Map.of("Z_X", 10.0, "X_L1", 100.0, "X_Y", 5.0, "Y_L2", 10.0));

        assertThat(plan.byEntity().get("X").recordCount()).isEqualTo(20);
        assertThat(plan.byEntity().get("Z").recordCount()).isEqualTo(2);
    }

    @Test
    void missingFkRaisesExplicitError() {
        LogicalSchema schema =
                schemaOf(
                        List.of(entity("Movie", pk("movie_id")), entity("Review", pk("review_id"))),
                        List.of(rel("Movie", "Review", RelationshipCardinality.ONE_TO_MANY)));

        assertThatThrownBy(() -> planner.plan(schema, List.of(new LeafChoice("Review", 100))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot resolve FK");
    }

    private static LogicalEntity entity(String name, LogicalAttribute... attrs) {
        return new LogicalEntity(name, "", List.of(attrs));
    }

    private static LogicalAttribute pk(String name) {
        return new LogicalAttribute(
                name,
                LogicalDataType.UUID,
                new AttributeConstraints(true, true, false, false, null),
                "",
                null,
                List.of(),
                null,
                null);
    }

    private static LogicalAttribute fk(String name) {
        return new LogicalAttribute(
                name,
                LogicalDataType.UUID,
                new AttributeConstraints(false, false, false, true, null),
                "",
                null,
                List.of(),
                null,
                null);
    }

    private static LogicalRelationship rel(
            String parent, String child, RelationshipCardinality card) {
        return new LogicalRelationship(
                parent + "_" + child, parent, child, card, "", List.of(), null);
    }

    private static LogicalSchema schemaOf(
            List<LogicalEntity> entities, List<LogicalRelationship> rels) {
        return new LogicalSchema("t", 1, List.of(), entities, rels, List.of());
    }
}
