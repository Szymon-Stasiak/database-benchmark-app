package com.dbagnets.backend.benchmark.controller;

import com.dbagnets.backend.benchmark.cascade.CascadeEdge;
import com.dbagnets.backend.benchmark.cascade.CascadeNode;
import com.dbagnets.backend.benchmark.cascade.CascadePlan;
import com.dbagnets.backend.benchmark.cascade.CascadePlanner;
import com.dbagnets.backend.benchmark.cascade.LeafChoice;
import com.dbagnets.backend.benchmark.execution.BenchmarkRun;
import com.dbagnets.backend.benchmark.execution.BenchmarkRunRepository;
import com.dbagnets.backend.benchmark.execution.ComparisonReportService;
import com.dbagnets.backend.benchmark.execution.DeleteRunOrchestrator;
import com.dbagnets.backend.benchmark.execution.InsertRunOrchestrator;
import com.dbagnets.backend.benchmark.execution.OperationType;
import com.dbagnets.backend.benchmark.execution.ReadRunOrchestrator;
import com.dbagnets.backend.benchmark.model.CascadePreviewRequest;
import com.dbagnets.backend.benchmark.model.CascadePreviewResponse;
import com.dbagnets.backend.benchmark.model.ComparisonReportResponse;
import com.dbagnets.backend.benchmark.model.DatabaseSizeResponse;
import com.dbagnets.backend.benchmark.model.DeleteRunResponse;
import com.dbagnets.backend.benchmark.model.EdgeRatioDto;
import com.dbagnets.backend.benchmark.model.EntityCascadeChoiceDto;
import com.dbagnets.backend.benchmark.model.EntityChoiceResponse;
import com.dbagnets.backend.benchmark.model.InsertRunResponse;
import com.dbagnets.backend.benchmark.model.ReadRunResponse;
import com.dbagnets.backend.benchmark.model.StartDeleteRunRequest;
import com.dbagnets.backend.benchmark.model.StartInsertRunRequest;
import com.dbagnets.backend.benchmark.model.StartReadRunRequest;
import com.dbagnets.backend.benchmark.schema.LogicalEntity;
import com.dbagnets.backend.benchmark.schema.LogicalRelationship;
import com.dbagnets.backend.benchmark.schema.LogicalSchema;
import com.dbagnets.backend.benchmark.schema.LogicalSchemaLoader;
import com.dbagnets.backend.benchmark.size.DataSizeProbe;
import com.dbagnets.backend.entity.Benchmark;
import com.dbagnets.backend.entity.BenchmarkDatabase;
import com.dbagnets.backend.repository.BenchmarkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class BenchmarkOpsController {

    private static final String HOST_ADDRESS = "127.0.0.1";

    private final BenchmarkRepository benchmarkRepository;
    private final BenchmarkRunRepository runRepository;
    private final LogicalSchemaLoader schemaLoader;
    private final CascadePlanner planner;
    private final InsertRunOrchestrator insertOrchestrator;
    private final ReadRunOrchestrator readOrchestrator;
    private final DeleteRunOrchestrator deleteOrchestrator;
    private final DataSizeProbe dataSizeProbe;
    private final ComparisonReportService comparisonReportService;

    @GetMapping("/benchmarks/{benchmarkId}/entities")
    public List<EntityChoiceResponse> listEntities(@PathVariable String benchmarkId) {
        LogicalSchema schema = loadSchema(benchmarkId);
        return schema.entities().stream()
                .map(this::toEntityChoice)
                .toList();
    }

    @PostMapping("/benchmarks/{benchmarkId}/cascade-preview")
    public CascadePreviewResponse previewCascade(@PathVariable String benchmarkId,
                                                  @RequestBody CascadePreviewRequest request) {
        LogicalSchema schema = loadSchema(benchmarkId);
        List<LeafChoice> leaves = request.entities().stream()
                .map(e -> new LeafChoice(e.entityName(), e.recordCount()))
                .toList();
        Map<String, Double> overrides = new HashMap<>();
        for (EntityCascadeChoiceDto entity : request.entities()) {
            for (EdgeRatioDto edge : entity.edgeRatios()) {
                overrides.put(edge.parentEntity() + "_" + edge.childEntity(), edge.ratio());
            }
        }
        CascadePlan plan = planner.plan(schema, leaves, overrides);
        return buildPreview(schema, plan, request);
    }

    @PostMapping("/benchmarks/{benchmarkId}/insert-runs")
    public ResponseEntity<InsertRunResponse> startInsertRun(@PathVariable String benchmarkId,
                                                             @RequestBody StartInsertRunRequest request) {
        BenchmarkRun run = insertOrchestrator.startRun(benchmarkId, request);
        return ResponseEntity.ok(InsertRunResponse.from(run));
    }

    @GetMapping("/benchmarks/{benchmarkId}/insert-runs")
    public List<InsertRunResponse> listInsertRuns(@PathVariable String benchmarkId) {
        return runRepository.findByBenchmarkIdAndOperationTypeOrderByCreatedAtDesc(benchmarkId, OperationType.INSERT)
                .stream()
                .map(InsertRunResponse::from)
                .toList();
    }

    @GetMapping("/insert-runs/{runId}")
    public InsertRunResponse getInsertRun(@PathVariable String runId) {
        BenchmarkRun run = runRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Insert run not found: " + runId));
        return InsertRunResponse.from(run);
    }

    @PostMapping("/benchmarks/{benchmarkId}/read-runs")
    public ResponseEntity<ReadRunResponse> startReadRun(@PathVariable String benchmarkId,
                                                         @RequestBody StartReadRunRequest request) {
        BenchmarkRun run = readOrchestrator.startRun(benchmarkId, request);
        return ResponseEntity.ok(ReadRunResponse.from(run, request.sampleSize(), request.includeChildren()));
    }

    @GetMapping("/benchmarks/{benchmarkId}/read-runs")
    public List<ReadRunResponse> listReadRuns(@PathVariable String benchmarkId) {
        return runRepository.findByBenchmarkIdAndOperationTypeOrderByCreatedAtDesc(benchmarkId, OperationType.READ)
                .stream()
                .map(r -> ReadRunResponse.from(r, null, null))
                .toList();
    }

    @GetMapping("/read-runs/{runId}")
    public ReadRunResponse getReadRun(@PathVariable String runId) {
        BenchmarkRun run = runRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Read run not found: " + runId));
        return ReadRunResponse.from(run, null, null);
    }

    @PostMapping("/benchmarks/{benchmarkId}/delete-runs")
    public ResponseEntity<DeleteRunResponse> startDeleteRun(@PathVariable String benchmarkId,
                                                             @RequestBody StartDeleteRunRequest request) {
        BenchmarkRun run = deleteOrchestrator.startRun(benchmarkId, request);
        return ResponseEntity.ok(DeleteRunResponse.from(run, request.sampleSize(), request.includeChildren()));
    }

    @GetMapping("/benchmarks/{benchmarkId}/delete-runs")
    public List<DeleteRunResponse> listDeleteRuns(@PathVariable String benchmarkId) {
        return runRepository.findByBenchmarkIdAndOperationTypeOrderByCreatedAtDesc(benchmarkId, OperationType.DELETE)
                .stream()
                .map(r -> DeleteRunResponse.from(r, null, null))
                .toList();
    }

    @GetMapping("/delete-runs/{runId}")
    public DeleteRunResponse getDeleteRun(@PathVariable String runId) {
        BenchmarkRun run = runRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Delete run not found: " + runId));
        return DeleteRunResponse.from(run, null, null);
    }

    @GetMapping("/benchmarks/{benchmarkId}/comparison-report")
    public ComparisonReportResponse getComparisonReport(@PathVariable String benchmarkId) {
        return comparisonReportService.build(benchmarkId);
    }

    @GetMapping("/benchmarks/{benchmarkId}/database-sizes")
    public List<DatabaseSizeResponse> getDatabaseSizes(@PathVariable String benchmarkId) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId)
                .orElseThrow(() -> new NoSuchElementException("Benchmark not found: " + benchmarkId));
        return benchmark.getDatabases().stream()
                .map(this::probeSize)
                .toList();
    }

    private DatabaseSizeResponse probeSize(BenchmarkDatabase db) {
        Long size = dataSizeProbe.sizeOf(db, HOST_ADDRESS);
        Long baseline = db.getBaselineSizeBytes();
        Long delta = (size != null && baseline != null) ? Math.max(0L, size - baseline) : null;
        return new DatabaseSizeResponse(
                db.getId(),
                db.getDbName(),
                db.getDbVersion(),
                size,
                baseline,
                delta,
                dataSizeProbe.humanize(size),
                size != null);
    }

    private CascadePreviewResponse buildPreview(LogicalSchema schema, CascadePlan plan, CascadePreviewRequest request) {
        List<String> leafNames = request.entities().stream().map(EntityCascadeChoiceDto::entityName).toList();
        List<CascadePreviewResponse.CascadePreviewEntity> entities = plan.nodesInInsertOrder().stream()
                .map(node -> new CascadePreviewResponse.CascadePreviewEntity(
                        node.entityName(),
                        node.recordCount(),
                        leafNames.contains(node.entityName()),
                        node.incomingFromParents().stream().map(CascadeEdge::parentEntity).toList()))
                .toList();
        List<CascadePreviewResponse.CascadePreviewEdge> edges = new java.util.ArrayList<>();
        for (CascadeNode node : plan.nodesInInsertOrder()) {
            for (CascadeEdge edge : node.incomingFromParents()) {
                LogicalRelationship rel = schema.relationships().stream()
                        .filter(r -> r.parentEntity().equals(edge.parentEntity())
                                && r.childEntity().equals(edge.childEntity()))
                        .findFirst()
                        .orElse(null);
                String cardinality = rel == null ? "ONE_TO_MANY" : rel.cardinality().name();
                double defaultRatio = rel == null ? edge.parentToChildRatio() : defaultRatio(rel);
                edges.add(new CascadePreviewResponse.CascadePreviewEdge(
                        edge.childEntity(),
                        edge.parentEntity(),
                        cardinality,
                        defaultRatio,
                        edge.parentToChildRatio(),
                        edge.fkColumnInChild()));
            }
        }
        return new CascadePreviewResponse(entities, edges);
    }

    private double defaultRatio(LogicalRelationship rel) {
        return switch (rel.cardinality()) {
            case ONE_TO_ONE -> 1.0;
            case ONE_TO_MANY -> 5.0;
            case MANY_TO_MANY -> 3.0;
        };
    }

    private EntityChoiceResponse toEntityChoice(LogicalEntity entity) {
        List<EntityChoiceResponse.AttributeChoice> attrs = entity.attributes().stream()
                .map(a -> new EntityChoiceResponse.AttributeChoice(
                        a.name(),
                        a.dataType().name(),
                        a.description(),
                        a.isPrimaryKey(),
                        a.isNullable()))
                .toList();
        return new EntityChoiceResponse(entity.name(), entity.description(), attrs);
    }

    private LogicalSchema loadSchema(String benchmarkId) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId)
                .orElseThrow(() -> new NoSuchElementException("Benchmark not found: " + benchmarkId));
        if (benchmark.getLogicalSchema() == null) {
            throw new IllegalStateException("Benchmark has no logical schema yet");
        }
        return schemaLoader.parse(benchmark.getLogicalSchema());
    }
}
