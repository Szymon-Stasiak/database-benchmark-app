package com.dbagnets.backend.engine.driver.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dbagnets.backend.engine.driver.api.EntityOutcome;
import com.dbagnets.backend.engine.timing.RecordedId;
import com.dbagnets.backend.engine.timing.TimedOperation;

class InsertAccumulatorTest {

    @Test
    void freshAccumulatorProducesZeros() {
        InsertAccumulator acc = new InsertAccumulator();

        TimedOperation result = acc.finish(1_000L);

        assertThat(result.dbTimeNs()).isZero();
        assertThat(result.wireTimeNs()).isEqualTo(1_000L);
        assertThat(result.rowsAffected()).isZero();
        assertThat(result.conflictsSkipped()).isZero();
        assertThat(result.recordedIds()).isEmpty();
    }

    @Test
    void acceptAggregatesFields() {
        InsertAccumulator acc = new InsertAccumulator();

        EntityOutcome one = new EntityOutcome();
        one.dbTimeNs = 100L;
        one.rowsAffected = 10L;
        one.conflicts = 2;
        one.recordedIds = List.of(new RecordedId("Users", "u1", "u1"));

        EntityOutcome two = new EntityOutcome();
        two.dbTimeNs = 200L;
        two.rowsAffected = 20L;
        two.conflicts = 3;
        two.recordedIds = List.of(new RecordedId("Orders", "o1", "o1"));

        acc.accept(one);
        acc.accept(two);

        TimedOperation result = acc.finish(500L);
        assertThat(result.dbTimeNs()).isEqualTo(300L);
        assertThat(result.wireTimeNs()).isEqualTo(500L);
        assertThat(result.rowsAffected()).isEqualTo(30L);
        assertThat(result.conflictsSkipped()).isEqualTo(5);
        assertThat(result.recordedIds()).hasSize(2);
    }
}
