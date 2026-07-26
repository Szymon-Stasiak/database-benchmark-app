package com.dbagnets.backend.benchmark.run.internal;

import java.util.List;

public record RunPreview(
        String rootEntity,
        int sampleSize,
        long availablePool,
        List<CascadeImpact> cascade
) {

    public record CascadeImpact(
            String entity,
            String parentEntity,
            String fkColumn,
            String cardinality,
            double ratio,
            long estimatedRowsAffected,
            int depth
    ) {
    }
}