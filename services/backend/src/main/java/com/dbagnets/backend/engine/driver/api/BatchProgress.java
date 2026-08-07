package com.dbagnets.backend.engine.driver.api;

public interface BatchProgress {
    void onBatch(
            String entityName, int batchIndex, int batchCount, long recordsDone, long recordsTotal);

    default void onEntityFinished(String entityName) {}
}
