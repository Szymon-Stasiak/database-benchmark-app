package com.dbagnets.backend.insert.datagen;

import com.dbagnets.backend.insert.schema.AttributeConstraints;
import com.dbagnets.backend.insert.schema.LogicalAttribute;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DataFakerServiceTest {

    private final DataFakerService service = new DataFakerService(new Faker(new Random(42)), new Random(42));

    @Test
    void uuidTypeReturnsStringWithUuidFormat() {
        Object v = service.generate(attr("id", "uuid", true, false));
        assertNotNull(v);
        assertTrue(v.toString().matches("[0-9a-f-]{36}"));
    }

    @Test
    void intTypeReturnsLong() {
        Object v = service.generate(attr("age", "int", false, false));
        assertNotNull(v);
        assertTrue(v instanceof Number);
    }

    @Test
    void decimalReturnsNumber() {
        Object v = service.generate(attr("price", "decimal", false, false));
        assertTrue(v instanceof Number);
    }

    @Test
    void booleanReturnsBoolean() {
        Object v = service.generate(attr("active", "boolean", false, false));
        assertTrue(v instanceof Boolean);
    }

    @Test
    void dateReturnsLocalDate() {
        Object v = service.generate(attr("created_on", "date", false, false));
        assertTrue(v instanceof LocalDate);
    }

    @Test
    void timestampReturnsInstant() {
        Object v = service.generate(attr("created_at", "timestamp", false, false));
        assertTrue(v instanceof Instant);
    }

    @Test
    void textReturnsString() {
        Object v = service.generate(attr("description", "text", false, false));
        assertTrue(v instanceof String);
    }

    @Test
    void emailColumnNameTriggersEmailFaker() {
        Object v = service.generate(attr("user_email", "varchar", false, false));
        assertNotNull(v);
        assertTrue(v.toString().contains("@"));
    }

    @Test
    void primaryKeyNeverReturnsNull() {
        for (int i = 0; i < 200; i++) {
            Object v = service.generate(attr("id", "int", true, true));
            assertNotNull(v, "primary key must not be null");
        }
    }

    @Test
    void unknownTypeFallsBackToWord() {
        Object v = service.generate(attr("misc", "unknown_type", false, false));
        assertTrue(v instanceof String);
    }

    private LogicalAttribute attr(String name, String type, boolean pk, boolean nullable) {
        return new LogicalAttribute(name, type, new AttributeConstraints(pk, false, nullable && !pk, false), null);
    }
}
