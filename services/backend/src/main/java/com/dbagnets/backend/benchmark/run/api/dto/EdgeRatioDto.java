package com.dbagnets.backend.benchmark.run.api.dto;

public record EdgeRatioDto(
        String childEntity,
        String parentEntity,
        double ratio
) {
}
