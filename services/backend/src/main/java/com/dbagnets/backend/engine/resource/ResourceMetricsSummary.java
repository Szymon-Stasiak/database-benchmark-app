package com.dbagnets.backend.engine.resource;

public record ResourceMetricsSummary(Double cpuPercentMax, Double cpuPercentMean, Double cpuPercentP95,
                                     Long memoryBytesMax, Long memoryBytesMean, Long memoryBytesP95,
                                     Integer sampleCount, String samplesJson) {
    public static ResourceMetricsSummary empty() {
        return new ResourceMetricsSummary(null, null, null, null, null, null, 0, null);
    }
}