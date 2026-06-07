package com.dbagnets.backend.controller;

import com.dbagnets.backend.docker.DockerService;
import com.dbagnets.backend.entity.User;
import com.dbagnets.backend.model.BenchmarkResponse;
import com.dbagnets.backend.model.CreateBenchmarkRequest;
import com.dbagnets.backend.model.LogsResponse;
import com.dbagnets.backend.security.CurrentUser;
import com.dbagnets.backend.service.BenchmarkService;
import com.dbagnets.backend.sse.SseEmitterService;
import com.dbagnets.backend.sse.SseEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/benchmarks")
@RequiredArgsConstructor
@Slf4j
public class BenchmarkController {

    private final BenchmarkService benchmarkService;
    private final SseEmitterService sseEmitterService;
    private final DockerService dockerService;
    private final ObjectMapper objectMapper;
    private final Executor sseInitExecutor = Executors.newCachedThreadPool();

    @PostMapping
    public ResponseEntity<BenchmarkResponse> createBenchmark(@Valid @RequestBody CreateBenchmarkRequest request, @CurrentUser User user) {
        BenchmarkResponse response = benchmarkService.createBenchmark(request, user);
        return ResponseEntity.status(201).body(response);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BenchmarkResponse> importBenchmark(@RequestParam("file") MultipartFile file, @CurrentUser User user) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Bundle file is required");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read uploaded bundle: " + e.getMessage(), e);
        }
        BenchmarkResponse response = benchmarkService.createFromBundle(bytes, user);
        return ResponseEntity.status(201).body(response);
    }

    @GetMapping("/{id}/bundle")
    public ResponseEntity<byte[]> downloadBundle(@PathVariable String id) {
        byte[] bundle = benchmarkService.downloadBundle(id);
        String filename = "benchmark-" + id + ".zip";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(bundle);
    }

    @GetMapping
    public List<BenchmarkResponse> listBenchmarks(@CurrentUser User user) {
        return benchmarkService.listBenchmarks(user);
    }

    @PostMapping("/{id}/redeploy")
    public ResponseEntity<Void> redeployBenchmark(@PathVariable String id) {
        benchmarkService.redeployBenchmark(id);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/hard-reset")
    public ResponseEntity<Void> hardResetBenchmark(@PathVariable String id) {
        benchmarkService.hardResetBenchmark(id);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/databases/{dbId}/redeploy")
    public ResponseEntity<Void> redeployDatabase(@PathVariable String id, @PathVariable String dbId) {
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
        sseInitExecutor.execute(() -> pushInitialState(id, emitter));
        return emitter;
    }

    @GetMapping("/{id}/databases/{dbId}/script/preview")
    public ResponseEntity<Map<String, String>> getScriptPreview(@PathVariable String dbId) {
        String preview = benchmarkService.getScriptPreview(dbId);
        if (preview == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("preview", preview));
    }

    @GetMapping("/{id}/databases/{dbId}/script")
    public ResponseEntity<byte[]> downloadScript(@PathVariable String dbId) {
        byte[] script = benchmarkService.downloadScript(dbId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"init-script.sql\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(script);
    }

    @DeleteMapping("/{id}/databases/{dbId}")
    public ResponseEntity<Void> deleteDatabase(@PathVariable String id, @PathVariable String dbId) {
        benchmarkService.deleteDatabase(id, dbId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/databases/{dbId}/stop")
    public ResponseEntity<Void> stopDatabase(@PathVariable String id, @PathVariable String dbId) {
        benchmarkService.stopDatabase(id, dbId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/databases/{dbId}/restart")
    public ResponseEntity<Void> restartDatabase(@PathVariable String id, @PathVariable String dbId) {
        benchmarkService.restartDatabase(id, dbId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/databases/{dbId}/logs")
    public LogsResponse getDatabaseLogs(@PathVariable String dbId, @RequestParam(defaultValue = "200") int tailLines) {
        String logs = benchmarkService.getDatabaseLogs(dbId, tailLines);
        return new LogsResponse(logs);
    }

    @GetMapping(value = "/{id}/databases/{dbId}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(@PathVariable String id, @PathVariable String dbId) {
        log.debug("Opening log stream for benchmark {} database {}", id, dbId);
        SseEmitter emitter = new SseEmitter(0L);
        benchmarkService.getContainerId(dbId).ifPresentOrElse(
                containerId -> dockerService.streamLogs(containerId, emitter),
                emitter::complete
        );
        return emitter;
    }

    private void pushInitialState(String benchmarkId, SseEmitter emitter) {
        try {
            Thread.sleep(SseEvents.INITIAL_STATE_DELAY_MS);
            BenchmarkResponse benchmark = benchmarkService.getBenchmark(benchmarkId);
            emitter.send(SseEmitter.event()
                    .name(SseEvents.EVENT_BENCHMARK_STATUS)
                    .data(objectMapper.writeValueAsString(
                        SseEvents.benchmarkStatusPayload(benchmarkId, benchmark.status()))));
            for (BenchmarkResponse.DatabaseResponse db : benchmark.databases()) {
                emitter.send(SseEmitter.event()
                        .name(SseEvents.EVENT_DATABASE_STATUS)
                        .data(objectMapper.writeValueAsString(
                            SseEvents.databaseStatusPayload(benchmarkId, db.id(), db.status(), db.errorMessage()))));
                if (db.hostPort() != null) {
                    emitter.send(SseEmitter.event()
                            .name(SseEvents.EVENT_DATABASE_PORT_ASSIGNED)
                            .data(objectMapper.writeValueAsString(
                                SseEvents.databasePortAssignedPayload(benchmarkId, db.id(), db.hostPort()))));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to push initial SSE state for benchmark {}: {}", benchmarkId, e.toString());
        }
    }
}