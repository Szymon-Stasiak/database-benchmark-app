package com.dbagnets.backend.insert.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * Converts generated values into per-database literal representations.
 *
 * <ul>
 *   <li>{@link #sqlLiteral(Object)} — escaped SQL literal (NULL for null, '...' for text, ISO-8601 for temporal).
 *   <li>{@link #jsonLiteral(Object, ObjectMapper)} — JSON-quoted value using the supplied mapper (Mongo, Couch, ES, ...).
 *   <li>{@link #cypherLiteral(Object, ObjectMapper)} — Cypher literal (same as JSON for primitives but with unquoted identifiers).
 * </ul>
 */
public final class ValueFormatter {

    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ValueFormatter() {}

    public static String sqlLiteral(Object value) {
        if (value == null) return "NULL";
        if (value instanceof Boolean b) return b ? "TRUE" : "FALSE";
        if (value instanceof Number n) return n.toString();
        if (value instanceof UUID u) return "'" + u + "'";
        if (value instanceof LocalDate d) return "'" + d + "'";
        if (value instanceof LocalTime t) return "'" + t + "'";
        if (value instanceof Instant i) return "'" + TIMESTAMP_FMT.format(i.atOffset(java.time.ZoneOffset.UTC)) + "'";
        if (value instanceof byte[] b) return "'" + bytesToHex(b) + "'";
        return "'" + escapeSqlString(value.toString()) + "'";
    }

    public static String jsonLiteral(Object value, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(normalizeForJson(value));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize value to JSON: " + value, e);
        }
    }

    public static String cypherLiteral(Object value, ObjectMapper mapper) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        return jsonLiteral(value, mapper);
    }

    public static Object normalizeForJson(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) return i.toString();
        if (value instanceof LocalDate d) return d.toString();
        if (value instanceof LocalTime t) return t.toString();
        if (value instanceof UUID u) return u.toString();
        return value;
    }

    private static String escapeSqlString(String s) {
        return s.replace("\\", "\\\\").replace("'", "''");
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}
