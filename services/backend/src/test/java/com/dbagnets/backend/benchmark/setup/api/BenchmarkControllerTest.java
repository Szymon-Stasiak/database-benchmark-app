package com.dbagnets.backend.benchmark.setup.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import com.dbagnets.backend.benchmark.setup.api.dto.BenchmarkResponse;
import com.dbagnets.backend.benchmark.setup.api.dto.CreateBenchmarkRequest;
import com.dbagnets.backend.benchmark.setup.application.BenchmarkLifecycleService;
import com.dbagnets.backend.benchmark.setup.application.BenchmarkOperationsService;
import com.dbagnets.backend.benchmark.setup.port.ContainerManagementPort;
import com.dbagnets.backend.shared.config.SecurityConfig;
import com.dbagnets.backend.shared.entity.User;
import com.dbagnets.backend.shared.event.BenchmarkEventPort;
import com.dbagnets.backend.shared.user.CurrentUserService;

@WebMvcTest(controllers = BenchmarkController.class)
@Import(SecurityConfig.class)
class BenchmarkControllerTest {

    private static final String CREATE_BODY =
            """
            {
              "topic": "favourite books",
              "depth": 3,
              "databases": [
                {"dbType": "RELATIONAL", "dbName": "postgresql", "dbVersion": "16"}
              ]
            }
            """;

    @Autowired MockMvc mockMvc;

    @MockBean JwtDecoder jwtDecoder;

    @MockBean BenchmarkLifecycleService lifecycle;

    @MockBean BenchmarkEventPort events;

    @MockBean BenchmarkOperationsService operations;

    @MockBean ContainerManagementPort containerManager;

    @MockBean CurrentUserService currentUserService;

    @Test
    void createBenchmarkDelegatesWithAuthenticatedUser() throws Exception {
        User alice = User.createFromJwtClaims("sub-alice", "alice@example.com", "Alice", "pic");
        BenchmarkResponse response =
                new BenchmarkResponse(
                        "bench-1",
                        "favourite books",
                        "PENDING",
                        Instant.parse("2026-05-29T10:00:00Z"),
                        null,
                        List.of());
        when(currentUserService.resolve(any(Jwt.class))).thenReturn(alice);
        when(lifecycle.createBenchmark(any(CreateBenchmarkRequest.class), same(alice)))
                .thenReturn(response);

        mockMvc.perform(
                        post("/api/benchmarks")
                                .with(
                                        jwt().jwt(
                                                        b ->
                                                                b.subject("sub-alice")
                                                                        .claim(
                                                                                "email",
                                                                                "alice@example.com")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("bench-1"))
                .andExpect(jsonPath("$.topic").value("favourite books"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(lifecycle).createBenchmark(any(CreateBenchmarkRequest.class), same(alice));
    }

    @Test
    void listBenchmarksReturnsOnlyAuthenticatedUsersBenchmarks() throws Exception {
        User alice = User.createFromJwtClaims("sub-alice", "alice@example.com", "Alice", "pic");
        User bob = User.createFromJwtClaims("sub-bob", "bob@example.com", "Bob", "pic");
        BenchmarkResponse aliceRun =
                new BenchmarkResponse(
                        "bench-alice",
                        "alice topic",
                        "RUNNING",
                        Instant.parse("2026-05-29T11:00:00Z"),
                        null,
                        List.of());
        BenchmarkResponse bobRun =
                new BenchmarkResponse(
                        "bench-bob",
                        "bob topic",
                        "PENDING",
                        Instant.parse("2026-05-29T12:00:00Z"),
                        null,
                        List.of());

        when(currentUserService.resolve(any(Jwt.class)))
                .thenAnswer(
                        inv -> {
                            Jwt jwt = inv.getArgument(0);
                            return "sub-alice".equals(jwt.getSubject()) ? alice : bob;
                        });
        when(lifecycle.listBenchmarks(same(alice))).thenReturn(List.of(aliceRun));
        when(lifecycle.listBenchmarks(same(bob))).thenReturn(List.of(bobRun));

        mockMvc.perform(
                        get("/api/benchmarks")
                                .with(
                                        jwt().jwt(
                                                        b ->
                                                                b.subject("sub-alice")
                                                                        .claim(
                                                                                "email",
                                                                                "alice@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("bench-alice"))
                .andExpect(jsonPath("$[0].topic").value("alice topic"));

        mockMvc.perform(
                        get("/api/benchmarks")
                                .with(
                                        jwt().jwt(
                                                        b ->
                                                                b.subject("sub-bob")
                                                                        .claim(
                                                                                "email",
                                                                                "bob@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("bench-bob"));
    }

    @Test
    void unauthenticatedListReturns401() throws Exception {
        mockMvc.perform(get("/api/benchmarks")).andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedCreateReturns401() throws Exception {
        mockMvc.perform(
                        post("/api/benchmarks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(CREATE_BODY))
                .andExpect(status().isUnauthorized());
    }
}
