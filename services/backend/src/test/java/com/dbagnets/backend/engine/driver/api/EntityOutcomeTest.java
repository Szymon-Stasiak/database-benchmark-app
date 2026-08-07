package com.dbagnets.backend.engine.driver.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dbagnets.backend.engine.timing.RecordedId;

class EntityOutcomeTest {

    @Test
    void defaultsAreZeroAndEmpty() {
        EntityOutcome outcome = new EntityOutcome();

        assertThat(outcome.dbTimeNs).isZero();
        assertThat(outcome.rowsAffected).isZero();
        assertThat(outcome.conflicts).isZero();
        assertThat(outcome.recordedIds).isEmpty();
    }

    @Test
    void fieldsAreMutable() {
        EntityOutcome outcome = new EntityOutcome();
        outcome.dbTimeNs = 100;
        outcome.rowsAffected = 10;
        outcome.conflicts = 2;
        outcome.recordedIds = List.of(new RecordedId("E", "l", "p"));

        assertThat(outcome.dbTimeNs).isEqualTo(100);
        assertThat(outcome.rowsAffected).isEqualTo(10);
        assertThat(outcome.conflicts).isEqualTo(2);
        assertThat(outcome.recordedIds).hasSize(1);
    }
}
