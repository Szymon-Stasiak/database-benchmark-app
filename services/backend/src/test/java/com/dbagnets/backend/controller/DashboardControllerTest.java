package com.dbagnets.backend.controller;

import com.dbagnets.backend.config.SecurityConfig;
import com.dbagnets.backend.entity.Benchmark;
import com.dbagnets.backend.entity.BenchmarkStatus;
import com.dbagnets.backend.entity.DatabaseStatus;
import com.dbagnets.backend.entity.User;
import com.dbagnets.backend.repository.BenchmarkDatabaseRepository;
import com.dbagnets.backend.repository.BenchmarkRepository;
import com.dbagnets.backend.service.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DashboardController.class)
@Import(SecurityConfig.class)
class DashboardControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    JwtDecoder jwtDecoder;

    @MockBean
    BenchmarkRepository benchmarkRepository;

    @MockBean
    BenchmarkDatabaseRepository databaseRepository;

    @MockBean
    CurrentUserService currentUserService;

    @Test
    void returnsDashboardForAuthenticatedUser() throws Exception {
        User alice = User.createFromJwtClaims("sub-alice", "alice@example.com", "Alice", "pic");
        Benchmark aliceBenchmark = mock(Benchmark.class);
        when(aliceBenchmark.getId()).thenReturn("bench-alice-1");
        when(aliceBenchmark.getTopic()).thenReturn("topic-alice");
        when(aliceBenchmark.getStatus()).thenReturn(BenchmarkStatus.RUNNING);
        when(aliceBenchmark.getCreatedAt()).thenReturn(Instant.parse("2026-05-29T12:00:00Z"));

        when(currentUserService.resolve(any(Jwt.class))).thenReturn(alice);
        when(benchmarkRepository.count()).thenReturn(7L);
        when(databaseRepository.countByStatus(DatabaseStatus.RUNNING)).thenReturn(3L);
        when(benchmarkRepository.findByUserOrderByCreatedAtDesc(same(alice)))
                .thenReturn(List.of(aliceBenchmark));

        mockMvc.perform(get("/api/dashboard").with(jwt().jwt(b -> b
                .subject("sub-alice")
                .claim("email", "alice@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBenchmarks").value(7))
                .andExpect(jsonPath("$.activeDatabases").value(3))
                .andExpect(jsonPath("$.recentRuns.length()").value(1))
                .andExpect(jsonPath("$.recentRuns[0].id").value("bench-alice-1"))
                .andExpect(jsonPath("$.recentRuns[0].databaseName").value("topic-alice"))
                .andExpect(jsonPath("$.recentRuns[0].status").value("RUNNING"));
    }

    @Test
    void recentRunsAreScopedToAuthenticatedUser() throws Exception {
        User alice = User.createFromJwtClaims("sub-alice", "alice@example.com", "Alice", "pic");
        User bob = User.createFromJwtClaims("sub-bob", "bob@example.com", "Bob", "pic");
        Benchmark bobBenchmark = mock(Benchmark.class);
        when(bobBenchmark.getId()).thenReturn("bench-bob-1");
        when(bobBenchmark.getTopic()).thenReturn("topic-bob");
        when(bobBenchmark.getStatus()).thenReturn(BenchmarkStatus.PENDING);
        when(bobBenchmark.getCreatedAt()).thenReturn(Instant.parse("2026-05-29T13:00:00Z"));

        when(currentUserService.resolve(any(Jwt.class))).thenAnswer(inv -> {
            Jwt jwt = inv.getArgument(0);
            return "sub-alice".equals(jwt.getSubject()) ? alice : bob;
        });
        when(benchmarkRepository.findByUserOrderByCreatedAtDesc(same(alice))).thenReturn(List.of());
        when(benchmarkRepository.findByUserOrderByCreatedAtDesc(same(bob))).thenReturn(List.of(bobBenchmark));

        mockMvc.perform(get("/api/dashboard").with(jwt().jwt(b -> b
                .subject("sub-alice").claim("email", "alice@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentRuns.length()").value(0));

        mockMvc.perform(get("/api/dashboard").with(jwt().jwt(b -> b
                .subject("sub-bob").claim("email", "bob@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentRuns.length()").value(1))
                .andExpect(jsonPath("$.recentRuns[0].id").value("bench-bob-1"));
    }

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/dashboard"))
                .andExpect(status().isUnauthorized());
    }
}
