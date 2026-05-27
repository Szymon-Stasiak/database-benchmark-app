package com.dbagnets.backend.insert.strategy.jdbc;

import java.util.List;

/**
 * Constraint information distilled from a Postgres CHECK clause (or similar) into a small set of
 * shapes the binding layer knows how to satisfy.
 *
 * <p>Why this exists: the script-creator generates CHECK constraints on custom DOMAIN types
 * (e.g. {@code star_rating} = NUMERIC with {@code VALUE BETWEEN 1.0 AND 5.0}). The DataFaker is
 * constraint-blind — it returns a random {@code 64479.0814} that the engine then rejects. Instead
 * of generating-and-praying we capture the constraint up-front and substitute a value the
 * constraint can prove satisfies it.
 */
public sealed interface ConstraintHint
    permits ConstraintHint.NumericRange, ConstraintHint.AllowedValues, ConstraintHint.RegexHint {

    /** Numeric column whose value must lie in {@code [min, max]} (both ends inclusive). */
    record NumericRange(double min, double max) implements ConstraintHint {
        public NumericRange {
            if (max < min) {
                double tmp = max; max = min; min = tmp;
            }
        }
    }

    /** Column whose value must come from a fixed string set — semantically equivalent to an ENUM. */
    record AllowedValues(List<String> values) implements ConstraintHint {
        public AllowedValues {
            values = List.copyOf(values);
        }
    }

    /**
     * Column whose value must match a regex. We don't try to satisfy arbitrary regexes — we just
     * tag the hint so the binder can substitute a faker-generated value of a known kind (email,
     * url) and, failing that, fall back to NULL on nullable columns.
     */
    record RegexHint(Kind kind, String pattern) implements ConstraintHint {
        public enum Kind { EMAIL, URL, OTHER }
    }
}
