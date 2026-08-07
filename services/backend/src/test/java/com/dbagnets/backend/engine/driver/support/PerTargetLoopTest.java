package com.dbagnets.backend.engine.driver.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.dbagnets.backend.engine.registry.EntityIdRegistry.RegistryEntry;
import com.dbagnets.backend.engine.timing.TimedOperation;

class PerTargetLoopTest {

    @Test
    void invokesHandlerForEachTargetAndAggregates() {
        List<RegistryEntry> targets = List.of(entry("id1"), entry("id2"), entry("id3"));

        TimedOperation result =
                PerTargetLoop.run(targets, "test", e -> e.physicalId(), entry -> 1L);

        assertThat(result.rowsAffected()).isEqualTo(3L);
        assertThat(result.sampleDbTimeNs()).hasSize(3);
    }

    @Test
    void exceptionInHandlerIsLoggedAndSkipped() {
        List<RegistryEntry> targets = List.of(entry("id1"), entry("id2"));
        AtomicInteger calls = new AtomicInteger();

        TimedOperation result =
                PerTargetLoop.run(
                        targets,
                        "test",
                        e -> e.physicalId(),
                        entry -> {
                            calls.incrementAndGet();
                            if (entry.physicalId().equals("id1"))
                                throw new RuntimeException("boom");
                            return 1L;
                        });

        assertThat(calls.get()).isEqualTo(2);
        assertThat(result.rowsAffected()).isEqualTo(1L);
    }

    @Test
    void emptyTargetsReturnEmptyResult() {
        TimedOperation result =
                PerTargetLoop.run(List.of(), "test", e -> e.physicalId(), entry -> 100L);

        assertThat(result.rowsAffected()).isZero();
        assertThat(result.sampleDbTimeNs()).isEmpty();
    }

    private RegistryEntry entry(String id) {
        return new RegistryEntry(id, id);
    }
}
