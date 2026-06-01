package com.dbagnets.backend.benchmark.driver;

public interface BatchProgress {
    void onBatch(String entityName, int batchIndex, int batchCount, long recordsDone, long recordsTotal);

    default void onEntityFinished(String entityName) {
    }

    BatchProgress NO_OP = new BatchProgress() {
        @Override
        public void onBatch(String entityName, int batchIndex, int batchCount, long recordsDone, long recordsTotal) {
        }
    };
}
