package com.dbagnets.backend.benchmark.result.api.dto;

import com.dbagnets.backend.shared.entity.Benchmark;

import java.util.List;

public record DashboardResponse(
        int totalBenchmarks,
        int activeDatabases,
        List<RecentRun> recentRuns
) {

    public record RecentRun(
            String id,
            String databaseName,
            String status,
            String timestamp
    ) {
        public static RecentRun from(Benchmark b) {
            return new RecentRun(
                    b.getId(),
                    b.getTopic(),
                    b.getStatus().name(),
                    b.getCreatedAt().toString()
            );
        }
    }
}