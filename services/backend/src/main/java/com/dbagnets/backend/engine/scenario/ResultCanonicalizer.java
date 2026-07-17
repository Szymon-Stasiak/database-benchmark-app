package com.dbagnets.backend.engine.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class ResultCanonicalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private ResultCanonicalizer() {
    }

    public static String canonicalize(Object value) {
        try {
            JsonNode node = MAPPER.valueToTree(value);
            JsonNode canonical = sortRecursively(node);
            return MAPPER.writeValueAsString(canonical);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to canonicalize result", e);
        }
    }

    public static String hash(String canonicalJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return toHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static ScenarioResult build(Object value, long rowsReturned) {
        String canonical = canonicalize(value);
        return new ScenarioResult(canonical, hash(canonical), rowsReturned);
    }

    private static JsonNode sortRecursively(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.sort(String::compareTo);
            for (String key : keys) {
                sorted.set(key, sortRecursively(node.get(key)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode arr = MAPPER.createArrayNode();
            Iterator<JsonNode> it = node.elements();
            while (it.hasNext()) {
                arr.add(sortRecursively(it.next()));
            }
            return arr;
        }
        return node;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
