package com.dbagnets.backend.engine.driver.support;

import java.util.List;
import java.util.Map;

import com.dbagnets.backend.engine.timing.TimedOperation;

public final class SampledAccumulator {

    private final long[] samples;
    private long dbTimeNs;
    private long rows;

    public SampledAccumulator(int count) {
        this.samples = new long[count];
    }

    public void sample(int index, long nanos, long rowCount) {
        samples[index] = nanos;
        dbTimeNs += nanos;
        rows += rowCount;
    }

    public TimedOperation finish(long wireTimeNs) {
        return TimedOperation.builder()
                .dbTimeNs(dbTimeNs)
                .wireTimeNs(wireTimeNs)
                .rowsAffected(rows)
                .sampleDbTimeNs(samples)
                .build();
    }

    public TimedOperation finishWithCascade(
            long wireTimeNs, Map<String, List<String>> cascadeDeletedByEntity) {
        return TimedOperation.builder()
                .dbTimeNs(dbTimeNs)
                .wireTimeNs(wireTimeNs)
                .rowsAffected(rows)
                .sampleDbTimeNs(samples)
                .cascadeDeletedByEntity(cascadeDeletedByEntity)
                .build();
    }
}
