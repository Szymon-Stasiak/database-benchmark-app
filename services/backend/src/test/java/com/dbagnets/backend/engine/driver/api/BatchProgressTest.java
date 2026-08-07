package com.dbagnets.backend.engine.driver.api;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class BatchProgressTest {

    @Test
    void onEntityFinishedDefaultsToNoOp() {
        BatchProgress progress = (entityName, batchIndex, batchCount, done, total) -> {};

        assertThatCode(() -> progress.onEntityFinished("Users")).doesNotThrowAnyException();
    }

    @Test
    void customImplementationInvokesOnBatch() {
        AtomicInteger calls = new AtomicInteger();
        BatchProgress progress =
                (entityName, batchIndex, batchCount, done, total) -> calls.incrementAndGet();

        progress.onBatch("Users", 1, 3, 100L, 300L);
        progress.onBatch("Users", 2, 3, 200L, 300L);

        assert calls.get() == 2;
    }
}
