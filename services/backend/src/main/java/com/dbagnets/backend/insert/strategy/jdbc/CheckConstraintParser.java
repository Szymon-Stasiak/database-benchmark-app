package com.dbagnets.backend.insert.strategy.jdbc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Postgres CHECK clause text into a {@link ConstraintHint}.
 *
 * <p>Postgres returns CHECK expressions in canonical form (e.g.
 * {@code CHECK (((VALUE >= (1.0)::double precision) AND (VALUE <= (5.0)::double precision)))}).
 * We recognise a small set of patterns that cover almost every shape the script-creator emits:
 *
 * <ul>
 *   <li>{@code VALUE >= X AND VALUE <= Y} → numeric range</li>
 *   <li>{@code VALUE BETWEEN X AND Y} → numeric range</li>
 *   <li>{@code VALUE >= X} (no upper bound) → numeric range with synthetic upper</li>
 *   <li>{@code VALUE = ANY (ARRAY['A','B','C'])} or {@code VALUE IN ('A','B')} → allowed strings</li>
 *   <li>{@code VALUE ~ '...email...'} → regex hint (EMAIL/URL/OTHER)</li>
 * </ul>
 *
 * Returns {@code null} when the expression matches no known pattern — the caller decides whether
 * to insert NULL (nullable columns) or take a best-effort attempt.
 */
public final class CheckConstraintParser {

    /** Matches a plain number with optional sign + decimal part. */
    private static final String NUM = "([+-]?\\d+(?:\\.\\d+)?)";

    /** Allow optional cast like {@code ::double precision} or {@code ::numeric} right after a number. */
    private static final String CAST_OPT = "(?:::[A-Za-z_ ]+)?";

    private static final Pattern RANGE_GE_LE = Pattern.compile(
        "VALUE\\s*>=\\s*\\(?" + NUM + "\\)?" + CAST_OPT + ".*?VALUE\\s*<=\\s*\\(?" + NUM + "\\)?" + CAST_OPT,
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern RANGE_LE_GE = Pattern.compile(
        "VALUE\\s*<=\\s*\\(?" + NUM + "\\)?" + CAST_OPT + ".*?VALUE\\s*>=\\s*\\(?" + NUM + "\\)?" + CAST_OPT,
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern RANGE_BETWEEN = Pattern.compile(
        "VALUE\\s+BETWEEN\\s+\\(?" + NUM + "\\)?" + CAST_OPT + "\\s+AND\\s+\\(?" + NUM + "\\)?" + CAST_OPT,
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Single-sided lower bound (no upper). Used as fallback when neither double-bound matches. */
    private static final Pattern LOWER_ONLY = Pattern.compile(
        "VALUE\\s*>=?\\s*\\(?" + NUM + "\\)?" + CAST_OPT,
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Single-sided upper bound (no lower). */
    private static final Pattern UPPER_ONLY = Pattern.compile(
        "VALUE\\s*<=?\\s*\\(?" + NUM + "\\)?" + CAST_OPT,
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Matches the literal list inside ARRAY[...] or IN (...) when the column is a string. */
    private static final Pattern ARRAY_LITERAL = Pattern.compile(
        "ARRAY\\s*\\[(.*?)\\]", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern IN_LITERAL = Pattern.compile(
        "VALUE\\s*IN\\s*\\(\\s*(.+?)\\s*\\)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Pulls a single-quoted SQL literal — handles doubled quotes inside. */
    private static final Pattern SQL_STRING = Pattern.compile("'((?:[^']|'')*)'");

    /** Matches a regex literal: VALUE ~ '...' or VALUE ~* '...' (case-insensitive). */
    private static final Pattern REGEX_MATCH = Pattern.compile(
        "VALUE\\)?(?:::[A-Za-z_ ]+)?\\s*~\\*?\\s*'((?:[^']|'')*)'",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private CheckConstraintParser() {}

    /** Returns a {@link ConstraintHint} for the CHECK clause, or {@code null} if unrecognised. */
    public static ConstraintHint parse(String checkClause) {
        if (checkClause == null || checkClause.isBlank()) return null;
        String s = checkClause;

        // --- Numeric ranges (in increasing specificity) -----------------------
        Matcher m = RANGE_BETWEEN.matcher(s);
        if (m.find()) {
            return new ConstraintHint.NumericRange(parseD(m.group(1)), parseD(m.group(2)));
        }
        m = RANGE_GE_LE.matcher(s);
        if (m.find()) {
            return new ConstraintHint.NumericRange(parseD(m.group(1)), parseD(m.group(2)));
        }
        m = RANGE_LE_GE.matcher(s);
        if (m.find()) {
            return new ConstraintHint.NumericRange(parseD(m.group(2)), parseD(m.group(1)));
        }

        // --- String enum-like (ANY(ARRAY[...]) or IN (...)) ------------------
        Matcher arr = ARRAY_LITERAL.matcher(s);
        if (arr.find()) {
            List<String> values = extractStringLiterals(arr.group(1));
            if (!values.isEmpty()) return new ConstraintHint.AllowedValues(values);
        }
        Matcher in = IN_LITERAL.matcher(s);
        if (in.find()) {
            List<String> values = extractStringLiterals(in.group(1));
            if (!values.isEmpty()) return new ConstraintHint.AllowedValues(values);
        }

        // --- Regex (email / url / other) -------------------------------------
        Matcher re = REGEX_MATCH.matcher(s);
        if (re.find()) {
            String pattern = re.group(1).toLowerCase(Locale.ROOT);
            ConstraintHint.RegexHint.Kind kind;
            if (pattern.contains("@")) kind = ConstraintHint.RegexHint.Kind.EMAIL;
            else if (pattern.contains("http") || pattern.contains("://")) kind = ConstraintHint.RegexHint.Kind.URL;
            else kind = ConstraintHint.RegexHint.Kind.OTHER;
            return new ConstraintHint.RegexHint(kind, pattern);
        }

        // --- Single-sided numeric bounds (last resort) -----------------------
        m = LOWER_ONLY.matcher(s);
        if (m.find()) {
            double min = parseD(m.group(1));
            // Pick a sensible upper bound so the random generator has space to work with.
            return new ConstraintHint.NumericRange(min, min + 1000.0);
        }
        m = UPPER_ONLY.matcher(s);
        if (m.find()) {
            double max = parseD(m.group(1));
            return new ConstraintHint.NumericRange(Math.min(0.0, max - 1000.0), max);
        }

        return null;
    }

    private static double parseD(String s) {
        return Double.parseDouble(s);
    }

    private static List<String> extractStringLiterals(String segment) {
        List<String> out = new ArrayList<>();
        Matcher m = SQL_STRING.matcher(segment);
        while (m.find()) {
            out.add(m.group(1).replace("''", "'"));
        }
        return out;
    }
}
