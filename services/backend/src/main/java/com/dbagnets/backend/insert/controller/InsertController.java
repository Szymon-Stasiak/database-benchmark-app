package com.dbagnets.backend.insert.controller;

import com.dbagnets.backend.insert.cascade.EdgeRatioOverride;
import com.dbagnets.backend.insert.model.CascadePreviewRequest;
import com.dbagnets.backend.insert.model.CascadePreviewResponse;
import com.dbagnets.backend.insert.model.DatabaseSizeResponse;
import com.dbagnets.backend.insert.model.EdgeRatio;
import com.dbagnets.backend.insert.model.EntityCascadeChoice;
import com.dbagnets.backend.insert.model.EntityChoice;
import com.dbagnets.backend.insert.model.InsertRunResponse;
import com.dbagnets.backend.insert.model.StartInsertRunRequest;
import com.dbagnets.backend.insert.service.DatabaseSizeService;
import com.dbagnets.backend.insert.service.EntityCatalogService;
import com.dbagnets.backend.insert.service.InsertOrchestrator;
import com.dbagnets.backend.sse.SseEmitterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@RestController
public class InsertController {

    private final EntityCatalogService entityCatalogService;
    private final InsertOrchestrator insertOrchestrator;
    private final DatabaseSizeService databaseSizeService;
    private final SseEmitterService sseEmitterService;
    private final ObjectMapper objectMapper;
    private final Executor snapshotExecutor = Executors.newCachedThreadPool();

    public InsertController(
        EntityCatalogService entityCatalogService,
        InsertOrchestrator insertOrchestrator,
        DatabaseSizeService databaseSizeService,
        SseEmitterService sseEmitterService,
        ObjectMapper objectMapper
    ) {
        this.entityCatalogService = entityCatalogService;
        this.insertOrchestrator = insertOrchestrator;
        this.databaseSizeService = databaseSizeService;
        this.sseEmitterService = sseEmitterService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/api/benchmarks/{benchmarkId}/entities")
    public List<EntityChoice> listEntities(@PathVariable String benchmarkId) {
        return entityCatalogService.listEntities(benchmarkId);
    }

    @PostMapping("/api/benchmarks/{benchmarkId}/cascade-preview")
    public CascadePreviewResponse cascadePreview(
        @PathVariable String benchmarkId,
        @Valid @RequestBody CascadePreviewRequest request
    ) {
        List<String> leafNames = new ArrayList<>(request.entities().size());
        Map<String, Integer> leafCounts = new HashMap<>(request.entities().size());
        List<EdgeRatioOverride> overrides = new ArrayList<>();
        for (EntityCascadeChoice c : request.entities()) {
            leafNames.add(c.entityName());
            leafCounts.put(c.entityName(), c.recordCount());
            for (EdgeRatio r : c.edgeRatiosOrEmpty()) {
                overrides.add(new EdgeRatioOverride(r.childEntity(), r.parentEntity(), r.ratio()));
            }
        }
        return entityCatalogService.cascadePreview(benchmarkId, leafNames, leafCounts, overrides);
    }

    @GetMapping("/api/benchmarks/{benchmarkId}/database-sizes")
    public List<DatabaseSizeResponse> databaseSizes(@PathVariable String benchmarkId) {
        return databaseSizeService.sizesFor(benchmarkId);
    }

    @PostMapping("/api/benchmarks/{benchmarkId}/insert-runs")
    public ResponseEntity<InsertRunResponse> startRun(
        @PathVariable String benchmarkId,
        @Valid @RequestBody StartInsertRunRequest request
    ) {
        InsertRunResponse response = insertOrchestrator.startRun(benchmarkId, request);
        return ResponseEntity.status(201).body(response);
    }

    @GetMapping("/api/benchmarks/{benchmarkId}/insert-runs")
    public List<InsertRunResponse> listRuns(@PathVariable String benchmarkId) {
        return insertOrchestrator.listRuns(benchmarkId);
    }

    @GetMapping("/api/insert-runs/{runId}")
    public InsertRunResponse getRun(@PathVariable String runId) {
        return insertOrchestrator.getRun(runId);
    }

    @GetMapping(value = "/api/insert-runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(@PathVariable String runId) {
        SseEmitter emitter = sseEmitterService.subscribe(runId);
        snapshotExecutor.execute(() -> {
            try {
                Thread.sleep(50);
                InsertRunResponse snapshot = insertOrchestrator.getRun(runId);
                emitter.send(SseEmitter.event()
                    .name("insert_run_status")
                    .data(objectMapper.writeValueAsString(Map.of(
                        "runId", runId,
                        "status", snapshot.status().name()
                    ))));
                for (var result : snapshot.results()) {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("id", result.id());
                    payload.put("databaseId", result.databaseId());
                    payload.put("dbName", result.dbName());
                    payload.put("entityName", result.entityName());
                    payload.put("status", result.status().name());
                    payload.put("startedAt", result.startedAt());
                    payload.put("finishedAt", result.finishedAt());
                    payload.put("durationMs", result.durationMs());
                    payload.put("recordsInserted", result.recordsInserted());
                    payload.put("throughputRps", result.throughputRps());
                    payload.put("errorMessage", result.errorMessage());
                    emitter.send(SseEmitter.event()
                        .name("insert_result_status")
                        .data(objectMapper.writeValueAsString(payload)));
                }
            } catch (Exception ignored) {
                // Snapshot is best-effort; live events continue to flow.
            }
        });
        return emitter;
    }
}
