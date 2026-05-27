package com.dbagnets.backend.insert.cascade;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimaryKeyRegistryTest {

    @Test
    void recordAndRandomFkReturnsOneOfTheRecordedValues() {
        PrimaryKeyRegistry reg = new PrimaryKeyRegistry(new Random(42));
        reg.record("User", List.of("u1", "u2", "u3"));
        Object pk = reg.randomFk("user");
        assertTrue(pk.equals("u1") || pk.equals("u2") || pk.equals("u3"));
    }

    @Test
    void randomFkOnMissingEntityReturnsNull() {
        PrimaryKeyRegistry reg = new PrimaryKeyRegistry();
        assertNull(reg.randomFk("nope"));
    }

    @Test
    void caseInsensitive() {
        PrimaryKeyRegistry reg = new PrimaryKeyRegistry(new Random(42));
        reg.record("UpperCaseEntity", List.of(1, 2));
        assertNotNull(reg.randomFk("uppercaseentity"));
        assertEquals(2, reg.size("UPPERCASEENTITY"));
    }

    @Test
    void concurrentRecordsAreAllVisible() throws InterruptedException {
        PrimaryKeyRegistry reg = new PrimaryKeyRegistry(new Random(42));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int t = 0; t < 8; t++) {
            final int threadId = t;
            pool.submit(() -> {
                for (int i = 0; i < 100; i++) {
                    reg.record("E", List.of("t" + threadId + "_" + i));
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(800, reg.size("E"));
    }
}
