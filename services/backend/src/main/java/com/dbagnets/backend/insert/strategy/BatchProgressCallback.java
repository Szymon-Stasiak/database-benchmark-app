package com.dbagnets.backend.insert.strategy;

/**
 * Receives one event per inserted batch (or per single record in SINGLE mode).
 *
 * <p>The orchestrator wires this to {@code SseEmitterService} so the frontend's progress bar
 * fills smoothly as work completes — without changing the insert strategy's interface every
 * time the SSE protocol evolves.
 */
@FunctionalInterface
public interface BatchProgressCallback {

    /**
     * @param batchIndex zero-based index of the batch that just finished (or record, in SINGLE mode).
     * @param batchCount total number of batches the strategy will produce.
     * @param recordsDone cumulative number of records successfully inserted so far.
     */
    void onBatch(int batchIndex, int batchCount, int recordsDone);

    BatchProgressCallback NO_OP = (i, c, r) -> {};
}
