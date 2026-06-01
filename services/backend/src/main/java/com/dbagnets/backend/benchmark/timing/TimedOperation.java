package com.dbagnets.backend.benchmark.timing;

import java.util.List;

public record TimedOperation(
        long dbTimeNs,
        long wireTimeNs,
        long rowsAffected,
        int conflictsSkipped,
        List<RecordedId> recordedIds,
        long[] sampleDbTimeNs
) {
    public TimedOperation {
        recordedIds = recordedIds == null ? List.of() : List.copyOf(recordedIds);
        sampleDbTimeNs = sampleDbTimeNs == null ? new long[0] : sampleDbTimeNs.clone();
    }

    public long overheadNs() {
        return Math.max(0L, wireTimeNs - dbTimeNs);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long dbTimeNs;
        private long wireTimeNs;
        private long rowsAffected;
        private int conflictsSkipped;
        private List<RecordedId> recordedIds = List.of();
        private long[] sampleDbTimeNs = new long[0];

        public Builder dbTimeNs(long v) { this.dbTimeNs = v; return this; }
        public Builder wireTimeNs(long v) { this.wireTimeNs = v; return this; }
        public Builder rowsAffected(long v) { this.rowsAffected = v; return this; }
        public Builder conflictsSkipped(int v) { this.conflictsSkipped = v; return this; }
        public Builder recordedIds(List<RecordedId> v) { this.recordedIds = v; return this; }
        public Builder sampleDbTimeNs(long[] v) { this.sampleDbTimeNs = v; return this; }

        public TimedOperation build() {
            return new TimedOperation(dbTimeNs, wireTimeNs, rowsAffected, conflictsSkipped, recordedIds, sampleDbTimeNs);
        }
    }
}
