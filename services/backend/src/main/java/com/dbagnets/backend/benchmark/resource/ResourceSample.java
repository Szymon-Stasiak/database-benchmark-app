package com.dbagnets.backend.benchmark.resource;

public record ResourceSample(
        long tMs,
        double cpuPercent,
        long memoryBytes,
        long memoryLimitBytes
) {
}
