package com.dbagnets.backend.engine.driver.support;

import com.dbagnets.backend.engine.driver.api.EngineDriver;
import com.dbagnets.backend.engine.scenario.ScenarioResult;
import com.dbagnets.backend.engine.timing.TimedOperation;

public final class ScenarioTimings {

    private ScenarioTimings() {}

    @FunctionalInterface
    public interface ScenarioBody {
        ScenarioResult execute() throws Exception;
    }

    public static EngineDriver.ScenarioOutcome execute(ScenarioBody body) throws Exception {
        long wireStart = System.nanoTime();
        long start = System.nanoTime();
        ScenarioResult result = body.execute();
        long dbTimeNs = System.nanoTime() - start;
        long wireTimeNs = System.nanoTime() - wireStart;

        TimedOperation timed =
                TimedOperation.builder()
                        .dbTimeNs(dbTimeNs)
                        .wireTimeNs(wireTimeNs)
                        .rowsAffected(result.rowsReturned())
                        .sampleDbTimeNs(new long[] {dbTimeNs})
                        .build();
        return new EngineDriver.ScenarioOutcome(timed, result);
    }
}
