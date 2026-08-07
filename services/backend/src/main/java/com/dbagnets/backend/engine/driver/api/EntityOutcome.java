package com.dbagnets.backend.engine.driver.api;

import java.util.ArrayList;
import java.util.List;

import com.dbagnets.backend.engine.timing.RecordedId;

public final class EntityOutcome {
    public long dbTimeNs;
    public long rowsAffected;
    public int conflicts;
    public List<RecordedId> recordedIds = new ArrayList<>();
}
