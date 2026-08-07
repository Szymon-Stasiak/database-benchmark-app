package com.dbagnets.backend.engine.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dbagnets.backend.engine.schema.AttributeConstraints;
import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalDataType;
import com.dbagnets.backend.engine.schema.LogicalRelationship;
import com.dbagnets.backend.engine.schema.LogicalSchema;
import com.dbagnets.backend.engine.schema.RelationshipCardinality;

class ScenarioSupportTest {

    @Test
    void numericTypesAreNumericLike() {
        assertThat(ScenarioSupport.isNumericLike(attr(LogicalDataType.INTEGER))).isTrue();
        assertThat(ScenarioSupport.isNumericLike(attr(LogicalDataType.BIGINT))).isTrue();
        assertThat(ScenarioSupport.isNumericLike(attr(LogicalDataType.FLOAT))).isTrue();
        assertThat(ScenarioSupport.isNumericLike(attr(LogicalDataType.DOUBLE))).isTrue();
        assertThat(ScenarioSupport.isNumericLike(attr(LogicalDataType.DECIMAL))).isTrue();
        assertThat(ScenarioSupport.isNumericLike(attr(LogicalDataType.DATE))).isTrue();
        assertThat(ScenarioSupport.isNumericLike(attr(LogicalDataType.TIMESTAMP))).isTrue();
    }

    @Test
    void textAndOtherTypesAreNotNumericLike() {
        assertThat(ScenarioSupport.isNumericLike(attr(LogicalDataType.STRING))).isFalse();
        assertThat(ScenarioSupport.isNumericLike(attr(LogicalDataType.TEXT))).isFalse();
        assertThat(ScenarioSupport.isNumericLike(attr(LogicalDataType.BOOLEAN))).isFalse();
        assertThat(ScenarioSupport.isNumericLike(attr(LogicalDataType.UUID))).isFalse();
        assertThat(ScenarioSupport.isNumericLike(attr(LogicalDataType.JSON))).isFalse();
        assertThat(ScenarioSupport.isNumericLike(attr(LogicalDataType.VECTOR))).isFalse();
        assertThat(ScenarioSupport.isNumericLike(attr(LogicalDataType.ENUM))).isFalse();
    }

    @Test
    void findRelationshipMatchesByParentAndChildCaseInsensitive() {
        LogicalRelationship rel =
                new LogicalRelationship(
                        "rel",
                        "Users",
                        "Orders",
                        RelationshipCardinality.ONE_TO_MANY,
                        "",
                        List.of(),
                        "user_id");
        LogicalSchema schema =
                new LogicalSchema("", 0, List.of(), List.of(), List.of(rel), List.of());

        LogicalRelationship found = ScenarioSupport.findRelationship(schema, "users", "ORDERS");

        assertThat(found).isSameAs(rel);
    }

    @Test
    void findRelationshipThrowsWhenNotFound() {
        LogicalSchema schema = new LogicalSchema("", 0, List.of(), List.of(), List.of(), List.of());

        assertThatThrownBy(() -> ScenarioSupport.findRelationship(schema, "A", "B"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent=A")
                .hasMessageContaining("child=B");
    }

    private LogicalAttribute attr(LogicalDataType type) {
        return new LogicalAttribute(
                "x", type, AttributeConstraints.NONE, "", null, List.of(), null, null);
    }
}
