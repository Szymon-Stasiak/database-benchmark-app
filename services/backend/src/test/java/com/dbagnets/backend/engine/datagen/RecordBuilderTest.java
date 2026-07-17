package com.dbagnets.backend.engine.datagen;

import com.dbagnets.backend.engine.cascade.CascadePlan;
import com.dbagnets.backend.engine.cascade.CascadePlanner;
import com.dbagnets.backend.engine.cascade.LeafChoice;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RecordBuilderTest {

    @Test
    void childRowsReferenceParentPksThatExistInVault() {
        LogicalSchema schema = new LogicalSchema("t", 1, List.of(),
                List.of(
                        new LogicalEntity("Movie", "",
                                List.of(pk("movie_id"))),
                        new LogicalEntity("Review", "",
                                List.of(pk("review_id"), fk("movie_id")))
                ),
                List.of(new LogicalRelationship("rel", "Movie", "Review",
                        RelationshipCardinality.ONE_TO_MANY, "", List.of(), null)),
                List.of());

        CascadePlan plan = new CascadePlanner().plan(schema, List.of(new LeafChoice("Review", 25)));
        PrimaryKeyVault vault = new PrimaryKeyVault();
        RecordBuilder builder = new RecordBuilder();

        Map<String, List<GeneratedRow>> all = builder.generateAll(schema, plan, vault);

        List<GeneratedRow> movies = all.get("Movie");
        List<GeneratedRow> reviews = all.get("Review");
        Set<String> movieIds = movies.stream().map(GeneratedRow::logicalId).collect(java.util.stream.Collectors.toSet());

        assertThat(movies).hasSize(5);
        assertThat(reviews).hasSize(25);
        for (GeneratedRow review : reviews) {
            assertThat(review.get("movie_id")).isIn(movieIds.toArray());
        }
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
}
