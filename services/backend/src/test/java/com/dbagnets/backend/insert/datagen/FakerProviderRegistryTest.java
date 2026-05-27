package com.dbagnets.backend.insert.datagen;

import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakerProviderRegistryTest {

    private final Faker faker = new Faker(new Random(42));
    private final FakerProviderRegistry registry = new FakerProviderRegistry(new Random(42));

    @Test
    void nullNameReturnsNull() {
        assertNull(registry.generateByName(faker, null));
    }

    @Test
    void nullTypeReturnsEmptyStringFallback() {
        Object v = registry.generateByType(faker, null);
        assertTrue(v instanceof String, "empty/null type falls through to text producer");
    }

    @Test
    void emailColumnRoutesToInternetEmail() {
        Object v = registry.generateByName(faker, "user_email");
        assertNotNull(v);
        assertTrue(v.toString().contains("@"));
    }

    @Test
    void ibanColumnRoutesToFinanceIban() {
        Object v = registry.generateByName(faker, "primary_iban");
        assertNotNull(v);
        assertTrue(v.toString().length() >= 15);
    }

    @Test
    void vinColumnRoutesToVehicleVin() {
        Object v = registry.generateByName(faker, "vehicle_vin");
        assertNotNull(v);
        assertEquals(17, v.toString().length(), "VIN is always 17 chars");
    }

    @Test
    void latitudeColumnRoutesToDouble() {
        Object v = registry.generateByName(faker, "pickup_latitude");
        assertTrue(v instanceof Double);
        double d = (double) v;
        assertTrue(d >= -90 && d <= 90, "latitude must be a valid degree value");
    }

    @Test
    void longitudeColumnRoutesToDouble() {
        Object v = registry.generateByName(faker, "drop_longitude");
        assertTrue(v instanceof Double);
        double d = (double) v;
        assertTrue(d >= -180 && d <= 180);
    }

    @Test
    void macAddressColumnRoutesToInternetMac() {
        Object v = registry.generateByName(faker, "device_mac_address");
        assertNotNull(v);
        assertTrue(v.toString().matches("[0-9a-fA-F:]+"));
    }

    @Test
    void ipv4ColumnRoutesToInternetIp() {
        Object v = registry.generateByName(faker, "client_ipv4");
        assertNotNull(v);
        assertTrue(v.toString().split("\\.").length == 4);
    }

    @Test
    void firstNameColumnRoutesToFirstName() {
        Object v = registry.generateByName(faker, "first_name");
        assertNotNull(v);
        assertFalse(v.toString().contains(" "), "first name should be a single token");
    }

    @Test
    void uuidColumnRoutesToUuidString() {
        Object v = registry.generateByName(faker, "session_uuid");
        assertNotNull(v);
        UUID.fromString(v.toString());
    }

    @Test
    void unknownColumnNameReturnsNull() {
        assertNull(registry.generateByName(faker, "completely_unmapped_xyz"));
    }

    @Test
    void uuidTypeReturnsUuidString() {
        Object v = registry.generateByType(faker, "uuid");
        UUID.fromString(v.toString());
    }

    @Test
    void integerLikeTypesReturnNumbers() {
        for (String type : new String[]{"int", "bigint", "smallint", "serial", "number", "long integer"}) {
            Object v = registry.generateByType(faker, type);
            assertTrue(v instanceof Number, "type " + type + " should produce a Number");
        }
    }

    @Test
    void timestampTypeReturnsInstant() {
        Object v = registry.generateByType(faker, "timestamp");
        assertTrue(v instanceof Instant);
    }

    @Test
    void exactDateTypeReturnsLocalDate() {
        Object v = registry.generateByType(faker, "date");
        assertTrue(v instanceof LocalDate);
    }

    @Test
    void timeTypeReturnsLocalTime() {
        Object v = registry.generateByType(faker, "time");
        assertTrue(v instanceof LocalTime);
    }

    @Test
    void vectorTypeReturnsDoubleArray() {
        Object v = registry.generateByType(faker, "vector");
        assertTrue(v instanceof double[]);
        assertEquals(8, ((double[]) v).length);
    }

    @Test
    void firstNameWinsOverGenericNamePattern() {
        Object first = registry.generateByName(faker, "first_name");
        Object generic = registry.generateByName(faker, "name");
        assertFalse(first.toString().contains(" "));
        assertTrue(generic.toString().contains(" "), "generic 'name' should produce a full name");
    }
}
