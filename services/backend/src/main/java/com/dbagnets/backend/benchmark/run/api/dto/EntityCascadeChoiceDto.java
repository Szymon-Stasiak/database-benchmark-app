package com.dbagnets.backend.benchmark.run.api.dto;

import java.util.List;

public record EntityCascadeChoiceDto(
        String entityName, long recordCount, List<EdgeRatioDto> edgeRatios) {
    public EntityCascadeChoiceDto {
        edgeRatios = edgeRatios == null ? List.of() : List.copyOf(edgeRatios);
    }
}
