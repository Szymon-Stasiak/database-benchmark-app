package com.dbagnets.backend.benchmark.setup.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record CreateBenchmarkRequest(
        @NotBlank String topic,
        @Min(1) @Max(10) int depth,
        @NotEmpty @Size(max = 5) List<@Valid DatabaseTarget> databases) {
    public record DatabaseTarget(
            @NotBlank String dbType, @NotBlank String dbName, @NotBlank String dbVersion) {}
}
