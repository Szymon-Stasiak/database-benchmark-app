package com.dbagnets.backend.controller;

import com.dbagnets.backend.entity.DatabaseStatus;
import com.dbagnets.backend.entity.User;
import com.dbagnets.backend.model.DashboardResponse;
import com.dbagnets.backend.model.DashboardResponse.RecentRun;
import com.dbagnets.backend.repository.BenchmarkDatabaseRepository;
import com.dbagnets.backend.repository.BenchmarkRepository;
import com.dbagnets.backend.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DashboardController {

    private static final int RECENT_RUNS_LIMIT = 5;

    private final BenchmarkRepository benchmarkRepository;
    private final BenchmarkDatabaseRepository databaseRepository;

    @GetMapping("/dashboard")
    public DashboardResponse getDashboard(@CurrentUser User user) {
        int totalBenchmarks = (int) benchmarkRepository.countByUser(user);
        int activeDatabases = (int) databaseRepository.countByBenchmark_UserAndStatus(user, DatabaseStatus.RUNNING);

        List<RecentRun> recentRuns = benchmarkRepository
                .findByUserOrderByCreatedAtDesc(user, PageRequest.of(0, RECENT_RUNS_LIMIT))
                .stream()
                .map(RecentRun::from)
                .toList();

        return new DashboardResponse(totalBenchmarks, activeDatabases, recentRuns);
    }
}