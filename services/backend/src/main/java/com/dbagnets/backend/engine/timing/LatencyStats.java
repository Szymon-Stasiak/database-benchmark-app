package com.dbagnets.backend.engine.timing;

import java.util.Arrays;

public record LatencyStats(long p50Ns, long p95Ns, long p99Ns, long meanNs, int sampleCount) {

    public static LatencyStats empty() {
        return new LatencyStats(0L, 0L, 0L, 0L, 0);
    }

    public static LatencyStats from(long[] samples) {
        if (samples == null || samples.length == 0) {
            return empty();
        }
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        long sum = 0L;
        for (long v : sorted) sum += v;
        long mean = sum / sorted.length;
        return new LatencyStats(
                percentile(sorted, 50),
                percentile(sorted, 95),
                percentile(sorted, 99),
                mean,
                sorted.length);
    }

    private static long percentile(long[] sorted, int p) {
        if (sorted.length == 1) return sorted[0];
        double rank = (p / 100.0) * (sorted.length - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) return sorted[lo];
        double frac = rank - lo;
        return Math.round(sorted[lo] + frac * (sorted[hi] - sorted[lo]));
    }
}
