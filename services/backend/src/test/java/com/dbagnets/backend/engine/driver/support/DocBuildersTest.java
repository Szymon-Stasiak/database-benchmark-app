package com.dbagnets.backend.engine.driver.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.dbagnets.backend.engine.datagen.GeneratedRow;
import com.dbagnets.backend.engine.schema.AttributeConstraints;
import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalDataType;
import com.dbagnets.backend.engine.schema.LogicalEntity;

class DocBuildersTest {

    private static final AttributeConstraints PK =
            new AttributeConstraints(true, true, false, false, null);
    private static final AttributeConstraints NONE = AttributeConstraints.NONE;

    @Test
    void withPkPutsPkFieldFirst() {
        LogicalEntity entity =
                entity(
                        attr("id", LogicalDataType.STRING, PK),
                        attr("name", LogicalDataType.STRING, NONE));

        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("id", "u1");
        values.put("name", "Alice");
        GeneratedRow row = new GeneratedRow("Users", "u1", values);

        Map<String, Object> doc = DocBuilders.withPk("_id", entity, row);

        assertThat(doc).containsEntry("_id", "u1").containsEntry("name", "Alice");
    }

    @Test
    void withPkAsStringCoercesToString() {
        LogicalEntity entity = entity(attr("id", LogicalDataType.INTEGER, PK));
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("id", 42);
        GeneratedRow row = new GeneratedRow("Users", "42", values);

        Map<String, Object> doc = DocBuilders.withPkAsString("_key", entity, row);

        assertThat(doc.get("_key")).isEqualTo("42");
    }

    @Test
    void withPkSkipsPkFieldWhenEntityHasNoPk() {
        LogicalEntity entity = entity(attr("name", LogicalDataType.STRING, NONE));

        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("name", "Bob");
        GeneratedRow row = new GeneratedRow("Users", "id", values);

        Map<String, Object> doc = DocBuilders.withPk("_id", entity, row);

        assertThat(doc).doesNotContainKey("_id");
        assertThat(doc).containsEntry("name", "Bob");
    }

    @Test
    void withPkCustomCoercerApplied() {
        LogicalEntity entity = entity(attr("id", LogicalDataType.STRING, PK));
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("id", "abc");
        GeneratedRow row = new GeneratedRow("Users", "abc", values);

        Map<String, Object> doc =
                DocBuilders.withPk("_id", entity, row, v -> ((String) v).toUpperCase());

        assertThat(doc.get("_id")).isEqualTo("ABC");
    }

    private LogicalEntity entity(LogicalAttribute... attrs) {
        return new LogicalEntity("Users", "desc", List.of(attrs));
    }

    private LogicalAttribute attr(String name, LogicalDataType type, AttributeConstraints c) {
        return new LogicalAttribute(name, type, c, "", null, List.of(), null, null);
    }
}
