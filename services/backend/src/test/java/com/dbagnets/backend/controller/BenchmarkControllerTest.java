package com.dbagnets.backend.controller;

import com.dbagnets.backend.config.SecurityConfig;
import com.dbagnets.backend.docker.DockerService;
import com.dbagnets.backend.entity.User;
import com.dbagnets.backend.model.BenchmarkResponse;
import com.dbagnets.backend.model.CreateBenchmarkRequest;
import com.dbagnets.backend.service.BenchmarkService;
import com.dbagnets.backend.service.CurrentUserService;
import com.dbagnets.backend.sse.SseEmitterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BenchmarkController.class)
@Import(SecurityConfig.class)
class BenchmarkControllerTest {

    private static final String CREATE_BODY = """
            {
              "topic": "favourite books",
              "depth": 3,
              "databases": [
                {"dbType": "RELATIONAL", "dbName": "postgresql", "dbVersion": "16"}
              ]
            }
            """;

    @Autowired
    MockMvc mockMvc;

    @MockBean
    JwtDecoder jwtDecoder;

    @MockBean
    BenchmarkService benchmarkService;

    @MockBean
    SseEmitterService sseEmitterService;

    @MockBean
    DockerService dockerService;

    @MockBean
    CurrentUserService currentUserService;

    @Test
    void createBenchmarkDelegatesWithAuthenticatedUser() throws Exception {
        User alice = User.createFromJwtClaims("sub-alice", "alice@example.com", "Alice", "pic");
        BenchmarkResponse response = new BenchmarkResponse(
                "bench-1", "favourite books", "PENDING", Instant.parse("2026-05-29T10:00:00Z"), null, List.of()
        );
        when(currentUserService.resolve(any(Jwt.class))).thenReturn(alice);
        when(benchmarkService.createBenchmark(any(CreateBenchmarkRequest.class), same(alice)))
                .thenReturn(response);

        mockMvc.perform(post("/api/benchmarks")
                .with(jwt().jwt(b -> b.subject("sub-alice").claim("email", "alice@example.com")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("bench-1"))
                .andExpect(jsonPath("$.topic").value("favourite books"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(benchmarkService).createBenchmark(any(CreateBenchmarkRequest.class), same(alice));
    }

    @Test
    void listBenchmarksReturnsOnlyAuthenticatedUsersBenchmarks() throws Exception {
        User alice = User.createFromJwtClaims("sub-alice", "alice@example.com", "Alice", "pic");
        User bob = User.createFromJwtClaims("sub-bob", "bob@example.com", "Bob", "pic");
        BenchmarkResponse aliceRun = new BenchmarkResponse(
                "bench-alice", "alice topic", "RUNNING", Instant.parse("2026-05-29T11:00:00Z"), null, List.of()
        );
        BenchmarkResponse bobRun = new BenchmarkResponse(
                "bench-bob", "bob topic", "PENDING", Instant.parse("2026-05-29T12:00:00Z"), null, List.of()
        );

        when(currentUserService.resolve(any(Jwt.class))).thenAnswer(inv -> {
            Jwt jwt = inv.getArgument(0);
            return "sub-alice".equals(jwt.getSubject()) ? alice : bob;
        });
        when(benchmarkService.listBenchmarks(same(alice))).thenReturn(List.of(aliceRun));
        when(benchmarkService.listBenchmarks(same(bob))).thenReturn(List.of(bobRun));

        mockMvc.perform(get("/api/benchmarks")
                .with(jwt().jwt(b -> b.subject("sub-alice").claim("email", "alice@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("bench-alice"))
                .andExpect(jsonPath("$[0].topic").value("alice topic"));

        mockMvc.perform(get("/api/benchmarks")
                .with(jwt().jwt(b -> b.subject("sub-bob").claim("email", "bob@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("bench-bob"));
    }

    @Test
    void unauthenticatedListReturns401() throws Exception {
        mockMvc.perform(get("/api/benchmarks"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedCreateReturns401() throws Exception {
        mockMvc.perform(post("/api/benchmarks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CREATE_BODY))
                .andExpect(status().isUnauthorized());
    }
}
