package com.dbagnets.backend.engine.driver.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.dbagnets.backend.engine.driver.api.EngineDriver;
import com.dbagnets.backend.engine.scenario.ResultCanonicalizer;

class ScenarioTimingsTest {

    @Test
    void executeCapturesTimingAndResult() throws Exception {
        var result = ResultCanonicalizer.build(Map.of("count", 5L), 5L);

        EngineDriver.ScenarioOutcome outcome = ScenarioTimings.execute(() -> result);

        assertThat(outcome.result()).isSameAs(result);
        assertThat(outcome.timed().rowsAffected()).isEqualTo(5L);
        assertThat(outcome.timed().dbTimeNs()).isGreaterThanOrEqualTo(0L);
        assertThat(outcome.timed().wireTimeNs()).isGreaterThanOrEqualTo(outcome.timed().dbTimeNs());
        assertThat(outcome.timed().sampleDbTimeNs()).hasSize(1);
    }

    @Test
    void executePropagatesExceptions() {
        assertThatThrownBy(
                        () ->
                                ScenarioTimings.execute(
                                        () -> {
                                            throw new IllegalStateException("nope");
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("nope");
    }
}
