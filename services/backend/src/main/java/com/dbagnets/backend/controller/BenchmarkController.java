package com.dbagnets.backend.controller;

import com.dbagnets.backend.docker.DockerService;
import com.dbagnets.backend.model.BenchmarkResponse;
import com.dbagnets.backend.model.CreateBenchmarkRequest;
import com.dbagnets.backend.model.LogsResponse;
import com.dbagnets.backend.service.BenchmarkService;
import com.dbagnets.backend.sse.SseEmitterService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/benchmarks")
public class BenchmarkController {

    private final BenchmarkService benchmarkService;
    private final SseEmitterService sseEmitterService;
    private final DockerService dockerService;
    private final ObjectMapper objectMapper;
    private final Executor sseInitExecutor = Executors.newCachedThreadPool();

    public BenchmarkController(BenchmarkService benchmarkService,
                                SseEmitterService sseEmitterService,
                                DockerService dockerService,
                                ObjectMapper objectMapper) {
        this.benchmarkService = benchmarkService;
        this.sseEmitterService = sseEmitterService;
        this.dockerService = dockerService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<BenchmarkResponse> createBenchmark(
            @Valid @RequestBody CreateBenchmarkRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        var response = benchmarkService.createBenchmark(request, email);
        return ResponseEntity.status(201).body(response);
    }

    @GetMapping
    public List<BenchmarkResponse> listBenchmarks(@AuthenticationPrincipal Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        return benchmarkService.listBenchmarks(email);
    }

    @PostMapping("/{id}/redeploy")
    public ResponseEntity<Void> redeployBenchmark(@PathVariable String id) {
        benchmarkService.redeployBenchmark(id);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/databases/{dbId}/redeploy")
    public ResponseEntity<Void> redeployDatabase(@PathVariable String id,
                                                  @PathVariable String dbId) {
        benchmarkService.redeployDatabase(id, dbId);
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBenchmark(@PathVariable String id) {
        benchmarkService.deleteBenchmark(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public BenchmarkResponse getBenchmark(@PathVariable String id) {
        return benchmarkService.getBenchmark(id);
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(@PathVariable String id) {
        SseEmitter emitter = sseEmitterService.subscribe(id);
        sseInitExecutor.execute(() -> {
            try {
                Thread.sleep(50);
                var benchmark = benchmarkService.getBenchmark(id);
                emitter.send(SseEmitter.event()
                    .name("benchmark_status")
                    .data(objectMapper.writeValueAsString(Map.of(
                        "benchmarkId", id,
                        "status", benchmark.status()
                    ))));
                for (var db : benchmark.databases()) {
                    var dbEvent = new HashMap<String, Object>();
                    dbEvent.put("benchmarkId", id);
                    dbEvent.put("databaseId", db.id());
                    dbEvent.put("status", db.status());
                    if (db.errorMessage() != null) {
                        dbEvent.put("errorMessage", db.errorMessage());
                    }
                    emitter.send(SseEmitter.event()
                        .name("database_status")
                        .data(objectMapper.writeValueAsString(dbEvent)));
                    if (db.hostPort() != null) {
                        emitter.send(SseEmitter.event()
                            .name("database_port_assigned")
                            .data(objectMapper.writeValueAsString(Map.of(
                                "benchmarkId", id,
                                "databaseId", db.id(),
                                "hostPort", db.hostPort()
                            ))));
                    }
                }
            } catch (Exception e) {
            }
        });
        return emitter;
    }

    @GetMapping("/{id}/databases/{dbId}/script/preview")
    public ResponseEntity<Map<String, String>> getScriptPreview(@PathVariable String id,
                                                                 @PathVariable String dbId) {
        String preview = benchmarkService.getScriptPreview(id, dbId);
        if (preview == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("preview", preview));
    }

    @GetMapping("/{id}/databases/{dbId}/script")
    public ResponseEntity<byte[]> downloadScript(@PathVariable String id,
                                                  @PathVariable String dbId) {
        byte[] script = benchmarkService.downloadScript(id, dbId);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"init-script.sql\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(script);
    }

    @PostMapping("/{id}/databases/{dbId}/stop")
    public ResponseEntity<Void> stopDatabase(@PathVariable String id,
                                              @PathVariable String dbId) {
        benchmarkService.stopDatabase(id, dbId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/databases/{dbId}/restart")
    public ResponseEntity<Void> restartDatabase(@PathVariable String id,
                                                 @PathVariable String dbId) {
        benchmarkService.restartDatabase(id, dbId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/databases/{dbId}/logs")
    public LogsResponse getDatabaseLogs(@PathVariable String id,
                                         @PathVariable String dbId,
                                         @RequestParam(defaultValue = "200") int tailLines) {
        String logs = benchmarkService.getDatabaseLogs(id, dbId, tailLines);
        return new LogsResponse(logs);
    }

    @GetMapping(value = "/{id}/databases/{dbId}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(@PathVariable String id,
                                  @PathVariable String dbId) {
        String containerId = benchmarkService.getContainerId(dbId);
        SseEmitter emitter = new SseEmitter(0L);
        if (containerId != null) {
            dockerService.streamLogs(containerId, emitter);
        } else {
            emitter.complete();
        }
        return emitter;
    }
}
