package com.dbagnets.backend.insert.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseSizeResponseTest {

    @Test
    void negativeSizeFlagsAsUnavailable() {
        DatabaseSizeResponse r = DatabaseSizeResponse.of("d1", "redis", "7", -1, null);
        assertFalse(r.available());
        assertNull(r.sizeBytes());
        assertEquals("n/a", r.sizeHuman());
    }

    @Test
    void bytesUnderOneKb() {
        DatabaseSizeResponse r = DatabaseSizeResponse.of("d1", "x", "1", 512, null);
        assertTrue(r.available());
        assertEquals(512L, r.sizeBytes());
        assertEquals("512 B", r.sizeHuman());
    }

    @Test
    void kilobytesAreFormatted() {
        assertEquals("1.50 KB", DatabaseSizeResponse.humanize(1536));
    }

    @Test
    void megabytesAreFormatted() {
        assertEquals("2.00 MB", DatabaseSizeResponse.humanize(2L * 1024 * 1024));
    }

    @Test
    void gigabytesAreFormatted() {
        assertEquals("3.00 GB", DatabaseSizeResponse.humanize(3L * 1024 * 1024 * 1024));
    }

    @Test
    void splitsBaselineFromDataWhenBaselinePresent() {
        DatabaseSizeResponse r = DatabaseSizeResponse.of("d1", "postgresql", "16",
            50L * 1024 * 1024, 30L * 1024 * 1024);
        assertEquals(50L * 1024 * 1024, r.sizeBytes());
        assertEquals(30L * 1024 * 1024, r.baselineBytes());
        assertEquals(20L * 1024 * 1024, r.dataBytes(), "data = current - baseline");
    }

    @Test
    void dataBytesNeverNegativeWhenBaselineLargerThanCurrent() {
        DatabaseSizeResponse r = DatabaseSizeResponse.of("d1", "postgresql", "16", 10_000, 50_000L);
        assertEquals(0L, r.dataBytes(), "negative deltas are clamped to 0");
    }
}
