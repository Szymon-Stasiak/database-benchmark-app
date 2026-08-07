package com.dbagnets.backend.engine.driver.support;

import java.util.ArrayList;
import java.util.List;

import com.dbagnets.backend.engine.driver.api.EntityOutcome;
import com.dbagnets.backend.engine.timing.RecordedId;
import com.dbagnets.backend.engine.timing.TimedOperation;

public final class InsertAccumulator {

    private long dbTimeNs;
    private long rowsAffected;
    private int conflicts;
    private final List<RecordedId> recordedIds = new ArrayList<>();

    public void accept(EntityOutcome outcome) {
        dbTimeNs += outcome.dbTimeNs;
        rowsAffected += outcome.rowsAffected;
        conflicts += outcome.conflicts;
        recordedIds.addAll(outcome.recordedIds);
    }

    public TimedOperation finish(long wireTimeNs) {
        return TimedOperation.builder()
                .dbTimeNs(dbTimeNs)
                .wireTimeNs(wireTimeNs)
                .rowsAffected(rowsAffected)
                .conflictsSkipped(conflicts)
                .recordedIds(recordedIds)
                .build();
    }
}
