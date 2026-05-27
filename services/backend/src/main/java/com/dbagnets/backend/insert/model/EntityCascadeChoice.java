package com.dbagnets.backend.insert.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * One leaf entity in a cascade insert request: name, the user-chosen record count, and any
 * per-edge ratio overrides for the parent chain leading up to it.
 */
public record EntityCascadeChoice(
    @NotBlank String entityName,

    @Min(value = 1, message = "recordCount must be >= 1")
    @Max(value = 100_000, message = "recordCount must be <= 100000")
    int recordCount,

    @Valid List<EdgeRatio> edgeRatios
) {
    public List<EdgeRatio> edgeRatiosOrEmpty() {
        return edgeRatios == null ? List.of() : edgeRatios;
    }
}
