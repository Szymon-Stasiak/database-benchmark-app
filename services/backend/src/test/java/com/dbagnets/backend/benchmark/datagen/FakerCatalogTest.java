package com.dbagnets.backend.benchmark.datagen;

import com.dbagnets.backend.benchmark.schema.AttributeConstraints;
import com.dbagnets.backend.benchmark.schema.LogicalAttribute;
import com.dbagnets.backend.benchmark.schema.LogicalDataType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FakerCatalogTest {

    private final FakerCatalog catalog = new FakerCatalog();

    @Test
    void primaryKeyUuidIsNeverNull() {
        LogicalAttribute pk = attr("id", LogicalDataType.UUID, true, false);
        for (int i = 0; i < 200; i++) {
            Object v = catalog.generate(pk);
            assertThat(v).isNotNull();
            assertThat(UUID.fromString((String) v)).isNotNull();
        }
    }

    @Test
    void nameLookupBeatsTypeLookup() {
        LogicalAttribute email = attr("email", LogicalDataType.STRING, false, false);
        Object v = catalog.generate(email);
        assertThat((String) v).contains("@");
    }

    @Test
    void enumPicksFromAllowedValues() {
        LogicalAttribute status = new LogicalAttribute(
                "status", LogicalDataType.ENUM,
                new AttributeConstraints(false, false, false, false, null),
                "", null, List.of("a", "b", "c"), null, null);
        for (int i = 0; i < 50; i++) {
            Object v = catalog.generate(status);
            assertThat(v).isIn("a", "b", "c");
        }
    }

    @Test
    void decimalRespectsScale() {
        LogicalAttribute price = new LogicalAttribute(
                "price", LogicalDataType.DECIMAL,
                new AttributeConstraints(false, false, false, false, null),
                "", null, List.of(), 6, 2);
        Object v = catalog.generate(price);
        assertThat(v).isInstanceOf(BigDecimal.class);
        assertThat(((BigDecimal) v).scale()).isEqualTo(2);
    }

    @Test
    void vectorMatchesRequestedDimension() {
        LogicalAttribute embedding = new LogicalAttribute(
                "embedding", LogicalDataType.VECTOR,
                new AttributeConstraints(false, false, false, false, null),
                "", 384, List.of(), null, null);
        Object v = catalog.generate(embedding);
        assertThat(v).isInstanceOf(float[].class);
        assertThat(((float[]) v).length).isEqualTo(384);
    }

    @Test
    void nullablePrimaryKeyNeverReturnsNull() {
        LogicalAttribute pk = attr("id", LogicalDataType.UUID, true, true);
        for (int i = 0; i < 200; i++) {
            assertThat(catalog.generate(pk)).isNotNull();
        }
    }

    private LogicalAttribute attr(String name, LogicalDataType type, boolean pk, boolean nullable) {
        return new LogicalAttribute(name, type,
                new AttributeConstraints(pk, false, nullable, false, null),
                "", null, List.of(), null, null);
    }
}
