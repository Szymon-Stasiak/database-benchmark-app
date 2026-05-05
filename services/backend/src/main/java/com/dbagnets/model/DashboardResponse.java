package com.dbagnets.backend.model;

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
    ) {}
}
