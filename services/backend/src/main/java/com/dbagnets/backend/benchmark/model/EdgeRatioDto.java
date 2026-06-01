package com.dbagnets.backend.benchmark.model;

public record EdgeRatioDto(
        String childEntity,
        String parentEntity,
        double ratio
) {
}
