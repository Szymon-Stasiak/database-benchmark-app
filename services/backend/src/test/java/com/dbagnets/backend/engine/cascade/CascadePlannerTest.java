package com.dbagnets.backend.engine.cascade;

import com.dbagnets.backend.engine.schema.AttributeConstraints;
import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalDataType;
import com.dbagnets.backend.engine.schema.LogicalEntity;
import com.dbagnets.backend.engine.schema.LogicalRelationship;
import com.dbagnets.backend.engine.schema.LogicalSchema;
import com.dbagnets.backend.engine.schema.RelationshipCardinality;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CascadePlannerTest {

    private final CascadePlanner planner = new CascadePlanner();

    @Test
    void singleLeafProducesPlanWithJustThatEntity() {
        LogicalSchema schema = schemaOf(
                List.of(entity("Genre", pk("genre_id"))),
                List.of()
        );
        CascadePlan plan = planner.plan(schema, List.of(new LeafChoice("Genre", 10)));

        assertThat(plan.nodesInInsertOrder()).hasSize(1);
        assertThat(plan.nodesInInsertOrder().get(0).entityName()).isEqualTo("Genre");
        assertThat(plan.nodesInInsertOrder().get(0).recordCount()).isEqualTo(10);
    }

    @Test
    void parentsArePlannedBeforeChildrenAndCountsPropagate() {
        LogicalSchema schema = schemaOf(
                List.of(
                        entity("Movie", pk("movie_id")),
                        entity("Review", pk("review_id"), fk("movie_id"))
                ),
                List.of(rel("Movie", "Review", RelationshipCardinality.ONE_TO_MANY))
        );

        CascadePlan plan = planner.plan(schema, List.of(new LeafChoice("Review", 100)));

        assertThat(plan.nodesInInsertOrder().stream().map(CascadeNode::entityName))
                .containsExactly("Movie", "Review");
        assertThat(plan.byEntity().get("Movie").recordCount()).isEqualTo(20);
        assertThat(plan.byEntity().get("Review").recordCount()).isEqualTo(100);
    }

    @Test
    void manyToManyRelationshipsAreSkipped() {
        LogicalSchema schema = schemaOf(
                List.of(
                        entity("Movie", pk("movie_id")),
                        entity("Actor", pk("actor_id"))
                ),
                List.of(rel("Movie", "Actor", RelationshipCardinality.MANY_TO_MANY))
        );

        CascadePlan plan = planner.plan(schema, List.of(new LeafChoice("Actor", 10)));

        assertThat(plan.nodesInInsertOrder()).hasSize(1);
        assertThat(plan.nodesInInsertOrder().get(0).entityName()).isEqualTo("Actor");
    }

    @Test
    void ratioOverrideAffectsPropagatedParentCount() {
        LogicalSchema schema = schemaOf(
                List.of(
                        entity("Movie", pk("movie_id")),
                        entity("Review", pk("review_id"), fk("movie_id"))
                ),
                List.of(rel("Movie", "Review", RelationshipCardinality.ONE_TO_MANY))
        );

        CascadePlan plan = planner.plan(schema,
                List.of(new LeafChoice("Review", 100)),
                Map.of("Movie_Review", 10.0));

        assertThat(plan.byEntity().get("Movie").recordCount()).isEqualTo(10);
    }

    @Test
    void missingFkRaisesExplicitError() {
        LogicalSchema schema = schemaOf(
                List.of(
                        entity("Movie", pk("movie_id")),
                        entity("Review", pk("review_id"))
                ),
                List.of(rel("Movie", "Review", RelationshipCardinality.ONE_TO_MANY))
        );

        assertThatThrownBy(() -> planner.plan(schema, List.of(new LeafChoice("Review", 100))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot resolve FK");
    }

    private static LogicalEntity entity(String name, LogicalAttribute... attrs) {
        return new LogicalEntity(name, "", List.of(attrs));
    }

    private static LogicalAttribute pk(String name) {
        return new LogicalAttribute(name, LogicalDataType.UUID,
                new AttributeConstraints(true, true, false, false, null),
                "", null, List.of(), null, null);
    }

    private static LogicalAttribute fk(String name) {
        return new LogicalAttribute(name, LogicalDataType.UUID,
                new AttributeConstraints(false, false, false, true, null),
                "", null, List.of(), null, null);
    }

    private static LogicalRelationship rel(String parent, String child, RelationshipCardinality card) {
        return new LogicalRelationship(parent + "_" + child, parent, child, card, "", List.of(), null);
    }

    private static LogicalSchema schemaOf(List<LogicalEntity> entities, List<LogicalRelationship> rels) {
        return new LogicalSchema("t", 1, List.of(), entities, rels, List.of());
    }
}
