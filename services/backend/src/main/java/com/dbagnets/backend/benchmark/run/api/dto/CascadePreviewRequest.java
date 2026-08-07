package com.dbagnets.backend.benchmark.run.api.dto;

import java.util.List;

public record CascadePreviewRequest(List<EntityCascadeChoiceDto> entities) {
    public CascadePreviewRequest {
        entities = entities == null ? List.of() : List.copyOf(entities);
    }
}
