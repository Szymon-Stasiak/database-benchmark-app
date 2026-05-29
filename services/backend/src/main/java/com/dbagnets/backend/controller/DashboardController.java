package com.dbagnets.backend.controller;

import com.dbagnets.backend.entity.DatabaseStatus;
import com.dbagnets.backend.entity.User;
import com.dbagnets.backend.model.DashboardResponse;
import com.dbagnets.backend.model.DashboardResponse.RecentRun;
import com.dbagnets.backend.repository.BenchmarkDatabaseRepository;
import com.dbagnets.backend.repository.BenchmarkRepository;
import com.dbagnets.backend.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DashboardController {

    private final BenchmarkRepository benchmarkRepository;
    private final BenchmarkDatabaseRepository databaseRepository;

    @GetMapping("/dashboard")
    public DashboardResponse getDashboard(@CurrentUser User user) {
        int totalBenchmarks = (int) benchmarkRepository.count();
        int activeDatabases = (int) databaseRepository.countByStatus(DatabaseStatus.RUNNING);

        List<RecentRun> recentRuns = benchmarkRepository
                .findByUserOrderByCreatedAtDesc(user)
                .stream()
                .limit(5)
                .map(b -> new RecentRun(
                        b.getId(),
                        b.getTopic(),
                        b.getStatus().name(),
                        b.getCreatedAt().toString()
                ))
                .toList();

        return new DashboardResponse(totalBenchmarks, activeDatabases, recentRuns);
    }
}