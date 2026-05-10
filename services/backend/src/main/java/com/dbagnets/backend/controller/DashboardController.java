package com.dbagnets.backend.controller;

import com.dbagnets.backend.entity.DatabaseStatus;
import com.dbagnets.backend.model.DashboardResponse;
import com.dbagnets.backend.model.DashboardResponse.RecentRun;
import com.dbagnets.backend.repository.BenchmarkDatabaseRepository;
import com.dbagnets.backend.repository.BenchmarkRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class DashboardController {

    private final BenchmarkRepository benchmarkRepository;
    private final BenchmarkDatabaseRepository databaseRepository;

    public DashboardController(BenchmarkRepository benchmarkRepository,
                                BenchmarkDatabaseRepository databaseRepository) {
        this.benchmarkRepository = benchmarkRepository;
        this.databaseRepository = databaseRepository;
    }

    @GetMapping("/dashboard")
    public DashboardResponse getDashboard(@AuthenticationPrincipal Jwt jwt) {
        String email = jwt.getClaimAsString("email");

        int totalBenchmarks = (int) benchmarkRepository.count();
        int activeDatabases = (int) databaseRepository.countByStatus(DatabaseStatus.RUNNING);

        List<RecentRun> recentRuns = benchmarkRepository
                .findByUserEmailOrderByCreatedAtDesc(email)
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
