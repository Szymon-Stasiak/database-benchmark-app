package com.dbagnets.backend.benchmark.driver;

import com.dbagnets.backend.benchmark.timing.TimedOperation;
import com.dbagnets.backend.entity.DatabaseEngine;

public interface EngineDriver {

    DatabaseEngine engine();

    TimedOperation insert(InsertContext ctx) throws Exception;

    default TimedOperation read(ReadContext ctx) throws Exception {
        throw new UnsupportedOperationException(engine() + " driver does not support READ yet");
    }

    default TimedOperation delete(DeleteContext ctx) throws Exception {
        throw new UnsupportedOperationException(engine() + " driver does not support DELETE yet");
    }

    default void close() {
    }
}
