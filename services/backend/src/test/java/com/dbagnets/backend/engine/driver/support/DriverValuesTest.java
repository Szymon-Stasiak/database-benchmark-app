package com.dbagnets.backend.engine.driver.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.dbagnets.backend.engine.datagen.GeneratedRow;

class DriverValuesTest {

    @Test
    void serializeFloatArrayBecomesListOfDoubles() {
        float[] arr = {1.0f, 2.5f, 3.0f};

        Object result = DriverValues.serialize(arr);

        assertThat(result).isInstanceOf(List.class);
        List<Object> list = new java.util.ArrayList<>((List<?>) result);
        assertThat(list).containsExactly(1.0, 2.5, 3.0);
    }

    @Test
    void serializeBigDecimalBecomesDouble() {
        Object result = DriverValues.serialize(new BigDecimal("42.75"));
        assertThat(result).isEqualTo(42.75);
    }

    @Test
    void serializeInstantBecomesString() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        assertThat(DriverValues.serialize(now)).isEqualTo("2026-01-01T00:00:00Z");
    }

    @Test
    void serializeLocalDateBecomesString() {
        LocalDate d = LocalDate.of(2026, 1, 15);
        assertThat(DriverValues.serialize(d)).isEqualTo("2026-01-15");
    }

    @Test
    void serializePassesThroughStringsAndNumbers() {
        assertThat(DriverValues.serialize("hello")).isEqualTo("hello");
        assertThat(DriverValues.serialize(42)).isEqualTo(42);
        assertThat(DriverValues.serialize(3.14)).isEqualTo(3.14);
        assertThat(DriverValues.serialize(true)).isEqualTo(true);
    }

    @Test
    void serializeNullReturnsNull() {
        assertThat(DriverValues.serialize(null)).isNull();
    }

    @Test
    void rowToMapReturnsAllValuesSerialized() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("id", "u1");
        values.put("age", 30);
        values.put("createdAt", Instant.parse("2026-01-01T00:00:00Z"));
        values.put("balance", new BigDecimal("100.50"));

        Map<String, Object> map = DriverValues.rowToMap(new GeneratedRow("Users", "u1", values));

        assertThat(map).containsEntry("id", "u1");
        assertThat(map).containsEntry("age", 30);
        assertThat(map).containsEntry("createdAt", "2026-01-01T00:00:00Z");
        assertThat(map).containsEntry("balance", 100.50);
    }

    @Test
    void rowToMapPreservesInsertionOrder() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("c", 3);
        values.put("a", 1);
        values.put("b", 2);

        Map<String, Object> map = DriverValues.rowToMap(new GeneratedRow("E", "id", values));

        assertThat(map.keySet()).containsExactly("c", "a", "b");
    }

    @Test
    void webClientMaxInMemoryBytesConstantIs16MiB() {
        assertThat(DriverValues.WEBCLIENT_MAX_IN_MEMORY_BYTES).isEqualTo(16 * 1024 * 1024);
    }
}
