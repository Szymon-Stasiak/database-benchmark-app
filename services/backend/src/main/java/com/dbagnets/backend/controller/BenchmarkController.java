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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
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
    public ResponseEntity<BenchmarkResponse> createBenchmark(
            @Valid @RequestBody CreateBenchmarkRequest request,
            @CurrentUser User user) {
        BenchmarkResponse response = benchmarkService.createBenchmark(request, user);
        return ResponseEntity.status(201).body(response);
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
        sseInitExecutor.execute(() -> pushInitialState(id, emitter));
        return emitter;
    }

    private void pushInitialState(String benchmarkId, SseEmitter emitter) {
        try {
            Thread.sleep(SseEvents.INITIAL_STATE_DELAY_MS);
            BenchmarkResponse benchmark = benchmarkService.getBenchmark(benchmarkId);
            emitter.send(SseEmitter.event()
                    .name(SseEvents.EVENT_BENCHMARK_STATUS)
                    .data(objectMapper.writeValueAsString(Map.of(
                            SseEvents.PAYLOAD_BENCHMARK_ID, benchmarkId,
                            SseEvents.PAYLOAD_STATUS, benchmark.status()
                    ))));
            for (BenchmarkResponse.DatabaseResponse db : benchmark.databases()) {
                HashMap<String, Object> dbEvent = new HashMap<>();
                dbEvent.put(SseEvents.PAYLOAD_BENCHMARK_ID, benchmarkId);
                dbEvent.put(SseEvents.PAYLOAD_DATABASE_ID, db.id());
                dbEvent.put(SseEvents.PAYLOAD_STATUS, db.status());
                if (db.errorMessage() != null) {
                    dbEvent.put(SseEvents.PAYLOAD_ERROR_MESSAGE, db.errorMessage());
                }
                emitter.send(SseEmitter.event()
                        .name(SseEvents.EVENT_DATABASE_STATUS)
                        .data(objectMapper.writeValueAsString(dbEvent)));
                if (db.hostPort() != null) {
                    emitter.send(SseEmitter.event()
                            .name(SseEvents.EVENT_DATABASE_PORT_ASSIGNED)
                            .data(objectMapper.writeValueAsString(Map.of(
                                    SseEvents.PAYLOAD_BENCHMARK_ID, benchmarkId,
                                    SseEvents.PAYLOAD_DATABASE_ID, db.id(),
                                    SseEvents.PAYLOAD_HOST_PORT, db.hostPort()
                            ))));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to push initial SSE state for benchmark {}: {}", benchmarkId, e.toString());
        }
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

    @DeleteMapping("/{id}/databases/{dbId}")
    public ResponseEntity<Void> deleteDatabase(@PathVariable String id,
                                               @PathVariable String dbId) {
        benchmarkService.deleteDatabase(id, dbId);
        return ResponseEntity.noContent().build();
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
    public SseEmitter streamLogs(@PathVariable String id, @PathVariable String dbId) {
        log.debug("Opening log stream for benchmark {} database {}", id, dbId);
        SseEmitter emitter = new SseEmitter(0L);
        benchmarkService.getContainerId(dbId).ifPresentOrElse(
                containerId -> dockerService.streamLogs(containerId, emitter),
                emitter::complete
        );
        return emitter;
    }
}