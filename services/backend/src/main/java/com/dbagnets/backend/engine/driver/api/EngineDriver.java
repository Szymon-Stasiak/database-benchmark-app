package com.dbagnets.backend.engine.driver.api;

import com.dbagnets.backend.engine.scenario.ScenarioContext;
import com.dbagnets.backend.engine.scenario.ScenarioResult;
import com.dbagnets.backend.engine.timing.TimedOperation;
import com.dbagnets.backend.domain.DatabaseEngine;

public interface EngineDriver {

    DatabaseEngine engine();

    TimedOperation insert(InsertContext ctx) throws Exception;

    default TimedOperation read(ReadContext ctx) throws Exception {
        throw new UnsupportedOperationException(engine() + " driver does not support READ yet");
    }

    default TimedOperation delete(DeleteContext ctx) throws Exception {
        throw new UnsupportedOperationException(engine() + " driver does not support DELETE yet");
    }

    default ScenarioOutcome runScenario(ScenarioContext ctx) throws Exception {
        throw new UnsupportedOperationException(
                engine() + " driver does not support scenario " + ctx.type());
    }

    record ScenarioOutcome(TimedOperation timed, ScenarioResult result) {
    }
}