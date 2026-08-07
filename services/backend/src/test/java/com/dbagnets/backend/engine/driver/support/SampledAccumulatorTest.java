package com.dbagnets.backend.engine.driver.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.dbagnets.backend.engine.timing.TimedOperation;

class SampledAccumulatorTest {

    @Test
    void samplePopulatesArrayAndAggregates() {
        SampledAccumulator acc = new SampledAccumulator(3);
        acc.sample(0, 100L, 1);
        acc.sample(1, 200L, 2);
        acc.sample(2, 300L, 3);

        TimedOperation result = acc.finish(1_000L);

        assertThat(result.dbTimeNs()).isEqualTo(600L);
        assertThat(result.wireTimeNs()).isEqualTo(1_000L);
        assertThat(result.rowsAffected()).isEqualTo(6);
        assertThat(result.sampleDbTimeNs()).containsExactly(100L, 200L, 300L);
    }

    @Test
    void emptySamplesReturnZeroTotals() {
        SampledAccumulator acc = new SampledAccumulator(2);

        TimedOperation result = acc.finish(500L);

        assertThat(result.dbTimeNs()).isZero();
        assertThat(result.rowsAffected()).isZero();
        assertThat(result.sampleDbTimeNs()).containsExactly(0L, 0L);
    }

    @Test
    void finishWithCascadeIncludesCascadeMap() {
        SampledAccumulator acc = new SampledAccumulator(1);
        acc.sample(0, 42L, 1);
        Map<String, List<String>> cascade = Map.of("Children", List.of("c1", "c2"));

        TimedOperation result = acc.finishWithCascade(100L, cascade);

        assertThat(result.cascadeDeletedByEntity()).isEqualTo(cascade);
        assertThat(result.rowsAffected()).isEqualTo(1);
    }
}
