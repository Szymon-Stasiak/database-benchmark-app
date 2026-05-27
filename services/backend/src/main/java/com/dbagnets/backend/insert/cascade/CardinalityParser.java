package com.dbagnets.backend.insert.cascade;

import java.util.Locale;

/**
 * Normalises free-form cardinality strings produced by the LLM into a {@link Cardinality} enum.
 *
 * <p>Defaults to {@link Cardinality#ONE_TO_MANY} when the value is null, blank, or unrecognised —
 * this is the most common case in practice and the user can override the ratio in the UI.
 */
public final class CardinalityParser {

    private CardinalityParser() {}

    public static Cardinality parse(String raw) {
        if (raw == null) return Cardinality.ONE_TO_MANY;
        String s = raw.trim().toLowerCase(Locale.ROOT)
            .replace(" ", "")
            .replace("-", ":")
            .replace("_", ":");
        if (s.isEmpty()) return Cardinality.ONE_TO_MANY;

        if (s.equals("1:1") || s.equals("one:to:one") || s.equals("onetoone")) {
            return Cardinality.ONE_TO_ONE;
        }
        if (s.equals("m:n") || s.equals("n:m") || s.equals("many:to:many") || s.equals("manytomany")) {
            return Cardinality.MANY_TO_MANY;
        }
        if (s.equals("1:n") || s.equals("1:m") || s.equals("one:to:many") || s.equals("onetomany")) {
            return Cardinality.ONE_TO_MANY;
        }
        return Cardinality.ONE_TO_MANY;
    }
}
