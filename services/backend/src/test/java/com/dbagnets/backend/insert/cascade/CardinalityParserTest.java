package com.dbagnets.backend.insert.cascade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CardinalityParserTest {

    @Test
    void nullDefaultsToOneToMany() {
        assertEquals(Cardinality.ONE_TO_MANY, CardinalityParser.parse(null));
    }

    @Test
    void blankDefaultsToOneToMany() {
        assertEquals(Cardinality.ONE_TO_MANY, CardinalityParser.parse("   "));
    }

    @Test
    void oneToOneVariants() {
        assertEquals(Cardinality.ONE_TO_ONE, CardinalityParser.parse("1:1"));
        assertEquals(Cardinality.ONE_TO_ONE, CardinalityParser.parse("1-1"));
        assertEquals(Cardinality.ONE_TO_ONE, CardinalityParser.parse("one-to-one"));
        assertEquals(Cardinality.ONE_TO_ONE, CardinalityParser.parse("ONE TO ONE"));
        assertEquals(Cardinality.ONE_TO_ONE, CardinalityParser.parse("onetoone"));
    }

    @Test
    void oneToManyVariants() {
        assertEquals(Cardinality.ONE_TO_MANY, CardinalityParser.parse("1:N"));
        assertEquals(Cardinality.ONE_TO_MANY, CardinalityParser.parse("1-N"));
        assertEquals(Cardinality.ONE_TO_MANY, CardinalityParser.parse("1:M"));
        assertEquals(Cardinality.ONE_TO_MANY, CardinalityParser.parse("one-to-many"));
        assertEquals(Cardinality.ONE_TO_MANY, CardinalityParser.parse("ONETOMANY"));
    }

    @Test
    void manyToManyVariants() {
        assertEquals(Cardinality.MANY_TO_MANY, CardinalityParser.parse("M:N"));
        assertEquals(Cardinality.MANY_TO_MANY, CardinalityParser.parse("N:M"));
        assertEquals(Cardinality.MANY_TO_MANY, CardinalityParser.parse("many-to-many"));
        assertEquals(Cardinality.MANY_TO_MANY, CardinalityParser.parse("MANYTOMANY"));
    }

    @Test
    void unrecognisedDefaultsToOneToMany() {
        assertEquals(Cardinality.ONE_TO_MANY, CardinalityParser.parse("something weird"));
    }

    @Test
    void defaultRatios() {
        assertEquals(1.0, Cardinality.ONE_TO_ONE.defaultRatio());
        assertEquals(5.0, Cardinality.ONE_TO_MANY.defaultRatio());
        assertEquals(5.0, Cardinality.MANY_TO_MANY.defaultRatio());
    }
}
