package com.dbagnets.backend.controller;

import com.dbagnets.backend.model.DashboardResponse;
import com.dbagnets.backend.model.DashboardResponse.RecentRun;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class DashboardController {

    @GetMapping("/dashboard")
    public DashboardResponse getDashboard() {
        List<RecentRun> recentRuns = List.of(
                new RecentRun("1", "PostgreSQL 16", "COMPLETED", "2026-05-05T10:00:00Z"),
                new RecentRun("2", "MongoDB 7", "COMPLETED", "2026-05-04T15:30:00Z"),
                new RecentRun("3", "Neo4j 5", "RUNNING", "2026-05-05T12:00:00Z")
        );

        return new DashboardResponse(42, 6, recentRuns);
    }
}
