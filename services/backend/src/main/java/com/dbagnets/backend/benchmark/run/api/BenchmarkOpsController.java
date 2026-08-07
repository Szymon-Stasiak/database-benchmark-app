package com.dbagnets.backend.benchmark.run.api;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dbagnets.backend.benchmark.result.api.dto.ComparisonReportResponse;
import com.dbagnets.backend.benchmark.result.api.dto.DatabaseSizeResponse;
import com.dbagnets.backend.benchmark.result.application.ComparisonReportService;
import com.dbagnets.backend.benchmark.result.application.DataSizeProbe;
import com.dbagnets.backend.benchmark.run.api.dto.BatchProgressEvent;
import com.dbagnets.backend.benchmark.run.api.dto.CascadePreviewRequest;
import com.dbagnets.backend.benchmark.run.api.dto.CascadePreviewResponse;
import com.dbagnets.backend.benchmark.run.api.dto.DeleteRunResponse;
import com.dbagnets.backend.benchmark.run.api.dto.EntityChoiceResponse;
import com.dbagnets.backend.benchmark.run.api.dto.InsertRunResponse;
import com.dbagnets.backend.benchmark.run.api.dto.PreparedRunResponse;
import com.dbagnets.backend.benchmark.run.api.dto.PreparedScenarioRunResponse;
import com.dbagnets.backend.benchmark.run.api.dto.ReadRunResponse;
import com.dbagnets.backend.benchmark.run.api.dto.ScenarioRunResponse;
import com.dbagnets.backend.benchmark.run.api.dto.StartDeleteRunRequest;
import com.dbagnets.backend.benchmark.run.api.dto.StartInsertRunRequest;
import com.dbagnets.backend.benchmark.run.api.dto.StartReadRunRequest;
import com.dbagnets.backend.benchmark.run.api.dto.StartScenarioRunRequest;
import com.dbagnets.backend.benchmark.run.application.BenchmarkRunSupport;
import com.dbagnets.backend.benchmark.run.application.EntitySamplerService;
import com.dbagnets.backend.benchmark.run.application.delete.DeleteRunOrchestrator;
import com.dbagnets.backend.benchmark.run.application.insert.InsertProgressTracker;
import com.dbagnets.backend.benchmark.run.application.insert.InsertRunOrchestrator;
import com.dbagnets.backend.benchmark.run.application.read.ReadRunOrchestrator;
import com.dbagnets.backend.benchmark.run.application.scenario.ScenarioApplicabilityService;
import com.dbagnets.backend.benchmark.run.application.scenario.ScenarioRunOrchestrator;
import com.dbagnets.backend.benchmark.run.internal.CascadePreviewService;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkRun;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkRunRepository;
import com.dbagnets.backend.benchmark.run.persistence.OperationType;
import com.dbagnets.backend.engine.registry.EntityIdRegistry;
import com.dbagnets.backend.engine.resource.ResourceSample;
import com.dbagnets.backend.engine.schema.LogicalSchema;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class BenchmarkOpsController {

    private final BenchmarkRunRepository runRepository;
    private final InsertRunOrchestrator insertOrchestrator;
    private final ReadRunOrchestrator readOrchestrator;
    private final DeleteRunOrchestrator deleteOrchestrator;
    private final ScenarioRunOrchestrator scenarioOrchestrator;
    private final InsertProgressTracker insertProgressTracker;
    private final DataSizeProbe dataSizeProbe;
    private final ComparisonReportService comparisonReportService;
    private final EntityIdRegistry registryService;
    private final BenchmarkRunSupport runSupport;
    private final EntitySamplerService entitySamplerService;
    private final CascadePreviewService cascadePreviewService;
    private final ScenarioApplicabilityService scenarioApplicabilityService;

    @GetMapping("/benchmarks/{benchmarkId}/entities")
    public List<EntityChoiceResponse> listEntities(@PathVariable String benchmarkId) {
        LogicalSchema schema = runSupport.loadSchema(benchmarkId);
        return schema.entities().stream().map(EntityChoiceResponse::from).toList();
    }

    @PostMapping("/benchmarks/{benchmarkId}/cascade-preview")
    public CascadePreviewResponse previewCascade(
            @PathVariable String benchmarkId, @RequestBody CascadePreviewRequest request) {
        LogicalSchema schema = runSupport.loadSchema(benchmarkId);
        return cascadePreviewService.previewFromRequest(schema, request);
    }

    @PostMapping("/benchmarks/{benchmarkId}/insert-runs")
    public ResponseEntity<InsertRunResponse> startInsertRun(
            @PathVariable String benchmarkId, @RequestBody StartInsertRunRequest request) {
        BenchmarkRun run = insertOrchestrator.startRun(benchmarkId, request);
        return ResponseEntity.ok(InsertRunResponse.from(run));
    }

    @GetMapping("/benchmarks/{benchmarkId}/insert-runs")
    public List<InsertRunResponse> listInsertRuns(@PathVariable String benchmarkId) {
        return runRepository
                .findByBenchmarkIdAndOperationTypeOrderByCreatedAtDesc(
                        benchmarkId, OperationType.INSERT)
                .stream()
                .map(InsertRunResponse::from)
                .toList();
    }

    @GetMapping("/insert-runs/{runId}")
    public InsertRunResponse getInsertRun(@PathVariable String runId) {
        BenchmarkRun run =
                runRepository
                        .findById(runId)
                        .orElseThrow(
                                () -> new NoSuchElementException("Insert run not found: " + runId));
        return InsertRunResponse.from(run);
    }

    @GetMapping("/insert-runs/{runId}/progress-snapshot")
    public List<BatchProgressEvent> getInsertProgressSnapshot(@PathVariable String runId) {
        return insertProgressTracker.snapshot(runId);
    }

    @PostMapping("/benchmarks/{benchmarkId}/read-runs/prepare")
    public PreparedRunResponse prepareReadRun(
            @PathVariable String benchmarkId, @RequestBody StartReadRunRequest request) {
        return readOrchestrator.prepareRun(benchmarkId, request);
    }

    @PostMapping("/read-runs/{runId}/confirm")
    public ResponseEntity<Void> confirmReadRun(@PathVariable String runId) {
        readOrchestrator.confirmRun(runId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/benchmarks/{benchmarkId}/read-runs")
    public ResponseEntity<ReadRunResponse> startReadRun(
            @PathVariable String benchmarkId, @RequestBody StartReadRunRequest request) {
        BenchmarkRun run = readOrchestrator.startRun(benchmarkId, request);
        return ResponseEntity.ok(
                ReadRunResponse.from(run, request.sampleSize(), request.includeChildren()));
    }

    @GetMapping("/benchmarks/{benchmarkId}/read-runs")
    public List<ReadRunResponse> listReadRuns(@PathVariable String benchmarkId) {
        return runRepository
                .findByBenchmarkIdAndOperationTypeOrderByCreatedAtDesc(
                        benchmarkId, OperationType.READ)
                .stream()
                .map(r -> ReadRunResponse.from(r, null, null))
                .toList();
    }

    @GetMapping("/read-runs/{runId}")
    public ReadRunResponse getReadRun(@PathVariable String runId) {
        BenchmarkRun run =
                runRepository
                        .findById(runId)
                        .orElseThrow(
                                () -> new NoSuchElementException("Read run not found: " + runId));
        return ReadRunResponse.from(run, null, null);
    }

    @PostMapping("/benchmarks/{benchmarkId}/delete-runs")
    public ResponseEntity<DeleteRunResponse> startDeleteRun(
            @PathVariable String benchmarkId, @RequestBody StartDeleteRunRequest request) {
        BenchmarkRun run = deleteOrchestrator.startRun(benchmarkId, request);
        return ResponseEntity.ok(
                DeleteRunResponse.from(run, request.sampleSize(), request.includeChildren()));
    }

    @PostMapping("/benchmarks/{benchmarkId}/delete-runs/prepare")
    public PreparedRunResponse prepareDeleteRun(
            @PathVariable String benchmarkId, @RequestBody StartDeleteRunRequest request) {
        return deleteOrchestrator.prepareRun(benchmarkId, request);
    }

    @PostMapping("/delete-runs/{runId}/confirm")
    public ResponseEntity<Void> confirmDeleteRun(@PathVariable String runId) {
        deleteOrchestrator.confirmRun(runId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/benchmarks/{benchmarkId}/registry-summary")
    public List<Map<String, Object>> getRegistrySummary(@PathVariable String benchmarkId) {
        LogicalSchema schema = runSupport.loadSchema(benchmarkId);
        return schema.entities().stream()
                .map(
                        e ->
                                Map.<String, Object>of(
                                        "entityName",
                                        e.name(),
                                        "availableIds",
                                        registryService.countLogicalIds(benchmarkId, e.name())))
                .toList();
    }

    @GetMapping("/benchmarks/{benchmarkId}/delete-runs")
    public List<DeleteRunResponse> listDeleteRuns(@PathVariable String benchmarkId) {
        return runRepository
                .findByBenchmarkIdAndOperationTypeOrderByCreatedAtDesc(
                        benchmarkId, OperationType.DELETE)
                .stream()
                .map(r -> DeleteRunResponse.from(r, null, null))
                .toList();
    }

    @GetMapping("/delete-runs/{runId}")
    public DeleteRunResponse getDeleteRun(@PathVariable String runId) {
        BenchmarkRun run =
                runRepository
                        .findById(runId)
                        .orElseThrow(
                                () -> new NoSuchElementException("Delete run not found: " + runId));
        return DeleteRunResponse.from(run, null, null);
    }

    @GetMapping("/benchmarks/{benchmarkId}/comparison-report")
    public ComparisonReportResponse getComparisonReport(@PathVariable String benchmarkId) {
        return comparisonReportService.build(benchmarkId);
    }

    @PostMapping("/benchmarks/{benchmarkId}/scenario-runs/prepare")
    public PreparedScenarioRunResponse prepareScenarioRun(
            @PathVariable String benchmarkId, @RequestBody StartScenarioRunRequest request) {
        return scenarioOrchestrator.prepareRun(benchmarkId, request);
    }

    @PostMapping("/scenario-runs/{runId}/confirm")
    public ResponseEntity<Void> confirmScenarioRun(@PathVariable String runId) {
        scenarioOrchestrator.confirmRun(runId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/benchmarks/{benchmarkId}/scenario-runs")
    public ResponseEntity<ScenarioRunResponse> startScenarioRun(
            @PathVariable String benchmarkId, @RequestBody StartScenarioRunRequest request) {
        BenchmarkRun run = scenarioOrchestrator.startRun(benchmarkId, request);
        return ResponseEntity.ok(ScenarioRunResponse.from(run));
    }

    @GetMapping("/benchmarks/{benchmarkId}/scenario-runs")
    public List<ScenarioRunResponse> listScenarioRuns(@PathVariable String benchmarkId) {
        return runRepository
                .findByBenchmarkIdAndOperationTypeOrderByCreatedAtDesc(
                        benchmarkId, OperationType.SCENARIO)
                .stream()
                .map(ScenarioRunResponse::from)
                .toList();
    }

    @GetMapping("/scenario-runs/{runId}")
    public ScenarioRunResponse getScenarioRun(@PathVariable String runId) {
        BenchmarkRun run =
                runRepository
                        .findById(runId)
                        .orElseThrow(
                                () ->
                                        new NoSuchElementException(
                                                "Scenario run not found: " + runId));
        return ScenarioRunResponse.from(run);
    }

    @GetMapping("/benchmarks/{benchmarkId}/entities/{entityName}/sample-id")
    public Map<String, String> sampleEntityId(
            @PathVariable String benchmarkId,
            @PathVariable String entityName,
            @RequestParam(required = false, defaultValue = "false") boolean withChildren) {
        if (withChildren) {
            String id = entitySamplerService.sampleParentIdWithChildren(benchmarkId, entityName);
            if (id != null) return Map.of("logicalId", id);
        }
        List<String> ids = registryService.sampleLogicalIds(benchmarkId, entityName, 1);
        return ids.isEmpty() ? Map.of() : Map.of("logicalId", ids.getFirst());
    }

    @GetMapping("/benchmarks/{benchmarkId}/relationships")
    public List<Map<String, Object>> listRelationships(@PathVariable String benchmarkId) {
        LogicalSchema schema = runSupport.loadSchema(benchmarkId);
        return schema.relationships().stream()
                .map(
                        r ->
                                Map.<String, Object>of(
                                        "name", r.name() == null ? "" : r.name(),
                                        "parentEntity", r.parentEntity(),
                                        "childEntity", r.childEntity(),
                                        "fkColumnInChild",
                                                r.fkColumnInChild() == null
                                                        ? ""
                                                        : r.fkColumnInChild(),
                                        "cardinality",
                                                r.cardinality() == null
                                                        ? ""
                                                        : r.cardinality().name()))
                .toList();
    }

    @GetMapping("/benchmarks/{benchmarkId}/scenario-applicability")
    public Map<String, List<String>> getScenarioApplicability(@PathVariable String benchmarkId) {
        return scenarioApplicabilityService.applicableDatabaseIdsByScenario(benchmarkId);
    }

    @GetMapping("/scenario-runs/{runId}/results/{resultId}/resource-timeline")
    public List<ResourceSample> getScenarioResourceTimeline(
            @PathVariable String runId, @PathVariable String resultId) {
        return runSupport.loadResourceTimeline(runId, resultId, OperationType.SCENARIO);
    }

    @GetMapping("/insert-runs/{runId}/results/{resultId}/resource-timeline")
    public List<ResourceSample> getInsertResourceTimeline(
            @PathVariable String runId, @PathVariable String resultId) {
        return runSupport.loadResourceTimeline(runId, resultId, OperationType.INSERT);
    }

    @GetMapping("/read-runs/{runId}/results/{resultId}/resource-timeline")
    public List<ResourceSample> getReadResourceTimeline(
            @PathVariable String runId, @PathVariable String resultId) {
        return runSupport.loadResourceTimeline(runId, resultId, OperationType.READ);
    }

    @GetMapping("/delete-runs/{runId}/results/{resultId}/resource-timeline")
    public List<ResourceSample> getDeleteResourceTimeline(
            @PathVariable String runId, @PathVariable String resultId) {
        return runSupport.loadResourceTimeline(runId, resultId, OperationType.DELETE);
    }

    @GetMapping("/benchmarks/{benchmarkId}/database-sizes")
    public List<DatabaseSizeResponse> getDatabaseSizes(@PathVariable String benchmarkId) {
        return dataSizeProbe.getDatabaseSizes(benchmarkId);
    }
}
