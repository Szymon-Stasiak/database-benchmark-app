package com.dbagnets.backend.engine.driver.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.dbagnets.backend.engine.driver.api.InsertContext;
import com.dbagnets.backend.engine.driver.api.InsertMode;

class BatchSizesTest {

    @Test
    void singleModeAlwaysReturnsOne() {
        InsertContext ctx = ctx(InsertMode.SINGLE, 500);
        assertThat(BatchSizes.effective(ctx, 1_000)).isEqualTo(1);
    }

    @Test
    void batchModeUsesContextBatchSize() {
        InsertContext ctx = ctx(InsertMode.BATCH, 250);
        assertThat(BatchSizes.effective(ctx, 1_000)).isEqualTo(250);
    }

    @Test
    void batchModeClampsToAtLeastOne() {
        InsertContext ctx = ctx(InsertMode.BATCH, 0);
        assertThat(BatchSizes.effective(ctx, 1_000)).isEqualTo(1);
    }

    @Test
    void bulkModeUsesContextBatchSizeWhenPositive() {
        InsertContext ctx = ctx(InsertMode.BULK, 5_000);
        assertThat(BatchSizes.effective(ctx, 1_000)).isEqualTo(5_000);
    }

    @Test
    void bulkModeUsesDefaultWhenContextIsZero() {
        InsertContext ctx = ctx(InsertMode.BULK, 0);
        assertThat(BatchSizes.effective(ctx, 1_000)).isEqualTo(1_000);
    }

    @Test
    void bulkModeUsesDefaultWhenContextIsNegative() {
        InsertContext ctx = ctx(InsertMode.BULK, -1);
        assertThat(BatchSizes.effective(ctx, 1_000)).isEqualTo(1_000);
    }

    @Test
    void effectiveCappedRespectsHardCap() {
        InsertContext ctx = ctx(InsertMode.BULK, 500);
        assertThat(BatchSizes.effectiveCapped(ctx, 1_000, 25)).isEqualTo(25);
    }

    @Test
    void effectiveCappedReturnsBaseWhenBelowCap() {
        InsertContext ctx = ctx(InsertMode.BATCH, 10);
        assertThat(BatchSizes.effectiveCapped(ctx, 1_000, 25)).isEqualTo(10);
    }

    @Test
    void effectiveCappedSingleModeAlsoCapped() {
        InsertContext ctx = ctx(InsertMode.SINGLE, 100);
        assertThat(BatchSizes.effectiveCapped(ctx, 1_000, 25)).isEqualTo(1);
    }

    private InsertContext ctx(InsertMode mode, int batchSize) {
        return new InsertContext(
                "bench",
                "db",
                "pg",
                "16",
                "localhost",
                5432,
                null,
                null,
                null,
                Map.of(),
                mode,
                batchSize,
                null);
    }
}
