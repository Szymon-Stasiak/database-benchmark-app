package com.dbagnets.backend.benchmark.model;

import java.util.List;

public record EntityCascadeChoiceDto(
        String entityName,
        long recordCount,
        List<EdgeRatioDto> edgeRatios
) {
    public EntityCascadeChoiceDto {
        edgeRatios = edgeRatios == null ? List.of() : List.copyOf(edgeRatios);
    }
}
