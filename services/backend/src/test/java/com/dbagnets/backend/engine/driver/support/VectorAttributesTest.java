package com.dbagnets.backend.engine.driver.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dbagnets.backend.engine.schema.AttributeConstraints;
import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalDataType;
import com.dbagnets.backend.engine.schema.LogicalEntity;

class VectorAttributesTest {

    @Test
    void findLocatesVectorAttribute() {
        LogicalEntity entity =
                new LogicalEntity(
                        "Docs",
                        "desc",
                        List.of(
                                attr("id", LogicalDataType.STRING),
                                attr("embedding", LogicalDataType.VECTOR),
                                attr("text", LogicalDataType.STRING)));

        LogicalAttribute found = VectorAttributes.find(entity);

        assertThat(found).isNotNull();
        assertThat(found.name()).isEqualTo("embedding");
    }

    @Test
    void findReturnsNullWhenNoVectorAttribute() {
        LogicalEntity entity =
                new LogicalEntity("Docs", "desc", List.of(attr("id", LogicalDataType.STRING)));

        assertThat(VectorAttributes.find(entity)).isNull();
    }

    @Test
    void toListConvertsFloatArrayToBoxedList() {
        float[] arr = {1.5f, 2.5f, 3.5f};

        List<Float> list = VectorAttributes.toList(arr);

        assertThat(list).containsExactly(1.5f, 2.5f, 3.5f);
    }

    @Test
    void toListEmptyArrayReturnsEmptyList() {
        assertThat(VectorAttributes.toList(new float[0])).isEmpty();
    }

    private LogicalAttribute attr(String name, LogicalDataType type) {
        return new LogicalAttribute(
                name, type, AttributeConstraints.NONE, "", null, List.of(), null, null);
    }
}
