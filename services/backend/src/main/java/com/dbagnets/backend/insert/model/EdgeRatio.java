package com.dbagnets.backend.insert.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

/**
 * User-supplied ratio override for one cascade edge ({@code child→parent}).
 * Wire format mirrored to/from the frontend.
 */
public record EdgeRatio(
    @NotBlank String childEntity,
    @NotBlank String parentEntity,
    @DecimalMin(value = "0.0001", message = "ratio must be > 0") double ratio
) {}
