package com.dbagnets.backend.benchmark.model;

import java.util.List;

public record CascadePreviewResponse(
        List<CascadePreviewEntity> entities,
        List<CascadePreviewEdge> edges
) {
    public record CascadePreviewEntity(
            String name,
            long recordCount,
            boolean leaf,
            List<String> parents
    ) {
    }

    public record CascadePreviewEdge(
            String childEntity,
            String parentEntity,
            String cardinality,
            double defaultRatio,
            double ratio,
            String fkColumn
    ) {
    }
}
