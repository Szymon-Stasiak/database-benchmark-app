package com.dbagnets.backend.insert.strategy.jdbc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class CheckConstraintParserTest {

    @Test
    void rangeWithCastsAndExtraParens() {
        // Exact form pg_get_constraintdef returns for star_rating.
        var hint = (ConstraintHint.NumericRange) CheckConstraintParser.parse(
            "CHECK (((VALUE >= (1.0)::double precision) AND (VALUE <= (5.0)::double precision)))");
        assertEquals(1.0, hint.min());
        assertEquals(5.0, hint.max());
    }

    @Test
    void rangeWithoutCasts() {
        var hint = (ConstraintHint.NumericRange) CheckConstraintParser.parse(
            "CHECK ((VALUE >= 0) AND (VALUE <= 10))");
        assertEquals(0.0, hint.min());
        assertEquals(10.0, hint.max());
    }

    @Test
    void rangeReversedOrder() {
        var hint = (ConstraintHint.NumericRange) CheckConstraintParser.parse(
            "CHECK ((VALUE <= 100) AND (VALUE >= 0))");
        assertEquals(0.0, hint.min());
        assertEquals(100.0, hint.max());
    }

    @Test
    void betweenSyntax() {
        var hint = (ConstraintHint.NumericRange) CheckConstraintParser.parse(
            "CHECK (VALUE BETWEEN 0.0 AND 100.0)");
        assertEquals(0.0, hint.min());
        assertEquals(100.0, hint.max());
    }

    @Test
    void lowerBoundOnlyGetsSyntheticUpper() {
        var hint = (ConstraintHint.NumericRange) CheckConstraintParser.parse(
            "CHECK (VALUE >= (0)::numeric)");
        assertEquals(0.0, hint.min());
        assertEquals(1000.0, hint.max(), "synthetic upper added so the random generator has room");
    }

    @Test
    void allowedValuesAnyArray() {
        var hint = (ConstraintHint.AllowedValues) CheckConstraintParser.parse(
            "CHECK (((VALUE)::text = ANY ((ARRAY['YES'::character varying, 'NO'::character varying])::text[])))");
        assertEquals(List.of("YES", "NO"), hint.values());
    }

    @Test
    void allowedValuesInList() {
        var hint = (ConstraintHint.AllowedValues) CheckConstraintParser.parse(
            "CHECK (VALUE IN ('low', 'medium', 'high'))");
        assertEquals(List.of("low", "medium", "high"), hint.values());
    }

    @Test
    void emailRegexDetected() {
        var hint = (ConstraintHint.RegexHint) CheckConstraintParser.parse(
            "CHECK (((VALUE)::text ~* '^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$'::text))");
        assertEquals(ConstraintHint.RegexHint.Kind.EMAIL, hint.kind());
    }

    @Test
    void urlRegexDetected() {
        var hint = (ConstraintHint.RegexHint) CheckConstraintParser.parse(
            "CHECK (((VALUE)::text ~* '^https?://.*'::text))");
        assertEquals(ConstraintHint.RegexHint.Kind.URL, hint.kind());
    }

    @Test
    void unknownExpressionReturnsNull() {
        assertNull(CheckConstraintParser.parse("CHECK (length(VALUE) > 0 AND complex_fn(VALUE) IS TRUE)"));
    }

    @Test
    void nullInputReturnsNull() {
        assertNull(CheckConstraintParser.parse(null));
    }

    @Test
    void typeHintReturnsAllowedValues() {
        assertInstanceOf(ConstraintHint.AllowedValues.class, CheckConstraintParser.parse(
            "CHECK (VALUE IN ('A', 'B'))"));
    }
}
