package com.dbagnets.backend.insert.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueFormatterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void nullSqlLiteral() {
        assertEquals("NULL", ValueFormatter.sqlLiteral(null));
    }

    @Test
    void booleanSqlLiteral() {
        assertEquals("TRUE", ValueFormatter.sqlLiteral(Boolean.TRUE));
        assertEquals("FALSE", ValueFormatter.sqlLiteral(Boolean.FALSE));
    }

    @Test
    void numberSqlLiteral() {
        assertEquals("42", ValueFormatter.sqlLiteral(42));
        assertEquals("3.14", ValueFormatter.sqlLiteral(3.14));
    }

    @Test
    void stringSqlLiteralEscapesQuotes() {
        assertEquals("'it''s'", ValueFormatter.sqlLiteral("it's"));
    }

    @Test
    void stringSqlLiteralEscapesBackslash() {
        assertEquals("'a\\\\b'", ValueFormatter.sqlLiteral("a\\b"));
    }

    @Test
    void uuidSqlLiteralIsQuoted() {
        UUID u = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        assertEquals("'123e4567-e89b-12d3-a456-426614174000'", ValueFormatter.sqlLiteral(u));
    }

    @Test
    void localDateIsoSqlLiteral() {
        assertEquals("'2025-01-01'", ValueFormatter.sqlLiteral(LocalDate.of(2025, 1, 1)));
    }

    @Test
    void instantSqlLiteralUsesUtcFormat() {
        String literal = ValueFormatter.sqlLiteral(Instant.parse("2025-01-01T12:34:56Z"));
        assertEquals("'2025-01-01 12:34:56'", literal);
    }

    @Test
    void jsonLiteralForMap() {
        String json = ValueFormatter.jsonLiteral(java.util.Map.of("a", 1), mapper);
        assertEquals("{\"a\":1}", json);
    }

    @Test
    void jsonLiteralNormalizesInstant() {
        String json = ValueFormatter.jsonLiteral(Instant.parse("2025-01-01T00:00:00Z"), mapper);
        assertTrue(json.contains("2025-01-01T00:00:00Z"));
    }

    @Test
    void cypherLiteralForNullReturnsNullKeyword() {
        assertEquals("null", ValueFormatter.cypherLiteral(null, mapper));
    }

    @Test
    void cypherLiteralForNumberSkipsQuotes() {
        assertEquals("42", ValueFormatter.cypherLiteral(42, mapper));
    }
}
