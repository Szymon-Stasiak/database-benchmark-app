package com.dbagnets.backend.insert.model;

import java.util.List;

/**
 * Reply to {@code GET /cascade-preview}: the topologically-ordered entities the user will end up
 * inserting and the editable parent edges along the way. Frontend uses this to render the
 * cascade-aware picker with auto-recomputed record counts.
 */
public record CascadePreviewResponse(
    List<PreviewEntity> entities,
    List<PreviewEdge> edges
) {
    public record PreviewEntity(String name, int recordCount, boolean leaf, List<String> parents) {}

    public record PreviewEdge(
        String childEntity,
        String parentEntity,
        String cardinality,
        double defaultRatio,
        double ratio,
        String fkColumn
    ) {}
}
