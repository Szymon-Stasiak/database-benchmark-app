package com.dbagnets.backend.benchmark.run.api;

import com.dbagnets.backend.engine.cascade.CascadeEdge;
import com.dbagnets.backend.engine.cascade.CascadeNode;
import com.dbagnets.backend.engine.cascade.CascadePlan;
import com.dbagnets.backend.engine.cascade.CascadePlanner;
import com.dbagnets.backend.engine.cascade.LeafChoice;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkResult;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkResultRepository;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkRun;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkRunRepository;
import com.dbagnets.backend.benchmark.result.application.ComparisonReportService;
import com.dbagnets.backend.benchmark.run.persistence.OperationType;
import com.dbagnets.backend.benchmark.run.application.delete.DeleteRunOrchestrator;
import com.dbagnets.backend.benchmark.run.application.insert.InsertProgressTracker;
import com.dbagnets.backend.benchmark.run.application.insert.InsertRunOrchestrator;
import com.dbagnets.backend.benchmark.run.application.read.ReadRunOrchestrator;
import com.dbagnets.backend.benchmark.run.application.scenario.ScenarioRunOrchestrator;
import com.dbagnets.backend.benchmark.run.api.dto.BatchProgressEvent;
import com.dbagnets.backend.benchmark.run.api.dto.CascadePreviewRequest;
import com.dbagnets.backend.benchmark.run.api.dto.CascadePreviewResponse;
import com.dbagnets.backend.benchmark.result.api.dto.ComparisonReportResponse;
import com.dbagnets.backend.benchmark.result.api.dto.DatabaseSizeResponse;
import com.dbagnets.backend.benchmark.run.api.dto.DeleteRunResponse;
import com.dbagnets.backend.benchmark.run.api.dto.EdgeRatioDto;
import com.dbagnets.backend.benchmark.run.api.dto.EntityCascadeChoiceDto;
import com.dbagnets.backend.benchmark.run.api.dto.EntityChoiceResponse;
import com.dbagnets.backend.benchmark.run.api.dto.InsertRunResponse;
import com.dbagnets.backend.benchmark.run.api.dto.PreparedRunResponse;
import com.dbagnets.backend.benchmark.run.api.dto.ReadRunResponse;
import com.dbagnets.backend.benchmark.run.api.dto.StartDeleteRunRequest;
import com.dbagnets.backend.benchmark.run.api.dto.StartInsertRunRequest;
import com.dbagnets.backend.benchmark.run.api.dto.StartReadRunRequest;
import com.dbagnets.backend.benchmark.run.api.dto.StartScenarioRunRequest;
import com.dbagnets.backend.benchmark.run.api.dto.PreparedScenarioRunResponse;
import com.dbagnets.backend.benchmark.run.api.dto.ScenarioRunResponse;
import com.dbagnets.backend.engine.driver.pg.PgConnectionInfo;
import com.dbagnets.backend.engine.driver.pg.PgDataSourceCache;
import com.dbagnets.backend.engine.registry.EntityIdRegistry;
import com.dbagnets.backend.engine.resource.ResourceSample;
import com.dbagnets.backend.engine.scenario.ScenarioApplicability;
import com.dbagnets.backend.engine.scenario.ScenarioType;
import com.dbagnets.backend.domain.DatabaseEngine;
import com.dbagnets.backend.domain.DatabaseStatus;
import com.dbagnets.backend.engine.schema.LogicalEntity;
import com.dbagnets.backend.engine.schema.LogicalRelationship;
import com.dbagnets.backend.engine.schema.LogicalSchema;
import com.dbagnets.backend.engine.schema.LogicalSchemaLoader;
import com.dbagnets.backend.benchmark.result.application.DataSizeProbe;
import com.dbagnets.backend.shared.entity.Benchmark;
import com.dbagnets.backend.shared.entity.BenchmarkDatabase;
import com.dbagnets.backend.infrastructure.persistence.BenchmarkRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final BenchmarkResultRepository resultRepository;
    private final LogicalSchemaLoader schemaLoader;
    private final CascadePlanner planner;
    private final InsertRunOrchestrator insertOrchestrator;
    private final ReadRunOrchestrator readOrchestrator;
    private final DeleteRunOrchestrator deleteOrchestrator;
    private final ScenarioRunOrchestrator scenarioOrchestrator;
    private final InsertProgressTracker insertProgressTracker;
    private final DataSizeProbe dataSizeProbe;
    private final ComparisonReportService comparisonReportService;
    private final EntityIdRegistry registryService;
    private final PgDataSourceCache pgDataSourceCache;
    private final ObjectMapper objectMapper;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BenchmarkOpsController.class);

    private static final TypeReference<List<ResourceSample>> RESOURCE_SAMPLES_TYPE = new TypeReference<>() {};

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

    @GetMapping("/insert-runs/{runId}/progress-snapshot")
    public List<BatchProgressEvent> getInsertProgressSnapshot(@PathVariable String runId) {
        return insertProgressTracker.snapshot(runId);
    }

    @PostMapping("/benchmarks/{benchmarkId}/read-runs")
    public ResponseEntity<ReadRunResponse> startReadRun(@PathVariable String benchmarkId,
                                                         @RequestBody StartReadRunRequest request) {
        BenchmarkRun run = readOrchestrator.startRun(benchmarkId, request);
        return ResponseEntity.ok(ReadRunResponse.from(run, request.sampleSize(), request.includeChildren()));
    }

    @PostMapping("/benchmarks/{benchmarkId}/read-runs/prepare")
    public PreparedRunResponse prepareReadRun(@PathVariable String benchmarkId,
                                               @RequestBody StartReadRunRequest request) {
        return readOrchestrator.prepareRun(benchmarkId, request);
    }

    @PostMapping("/read-runs/{runId}/confirm")
    public ResponseEntity<Void> confirmReadRun(@PathVariable String runId) {
        readOrchestrator.confirmRun(runId);
        return ResponseEntity.noContent().build();
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

    @PostMapping("/benchmarks/{benchmarkId}/delete-runs/prepare")
    public PreparedRunResponse prepareDeleteRun(@PathVariable String benchmarkId,
                                                 @RequestBody StartDeleteRunRequest request) {
        return deleteOrchestrator.prepareRun(benchmarkId, request);
    }

    @PostMapping("/delete-runs/{runId}/confirm")
    public ResponseEntity<Void> confirmDeleteRun(@PathVariable String runId) {
        deleteOrchestrator.confirmRun(runId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/benchmarks/{benchmarkId}/registry-summary")
    public List<Map<String, Object>> getRegistrySummary(@PathVariable String benchmarkId) {
        LogicalSchema schema = loadSchema(benchmarkId);
        return schema.entities().stream()
                .map(e -> Map.<String, Object>of(
                        "entityName", e.name(),
                        "availableIds", registryService.countLogicalIds(benchmarkId, e.name())))
                .toList();
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

    @PostMapping("/benchmarks/{benchmarkId}/scenario-runs/prepare")
    public PreparedScenarioRunResponse prepareScenarioRun(@PathVariable String benchmarkId,
                                                            @RequestBody StartScenarioRunRequest request) {
        return scenarioOrchestrator.prepareRun(benchmarkId, request);
    }

    @PostMapping("/scenario-runs/{runId}/confirm")
    public ResponseEntity<Void> confirmScenarioRun(@PathVariable String runId) {
        scenarioOrchestrator.confirmRun(runId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/benchmarks/{benchmarkId}/scenario-runs")
    public ResponseEntity<ScenarioRunResponse> startScenarioRun(@PathVariable String benchmarkId,
                                                                  @RequestBody StartScenarioRunRequest request) {
        BenchmarkRun run = scenarioOrchestrator.startRun(benchmarkId, request);
        return ResponseEntity.ok(ScenarioRunResponse.from(run));
    }

    @GetMapping("/benchmarks/{benchmarkId}/scenario-runs")
    public List<ScenarioRunResponse> listScenarioRuns(@PathVariable String benchmarkId) {
        return runRepository.findByBenchmarkIdAndOperationTypeOrderByCreatedAtDesc(benchmarkId, OperationType.SCENARIO)
                .stream()
                .map(ScenarioRunResponse::from)
                .toList();
    }

    @GetMapping("/scenario-runs/{runId}")
    public ScenarioRunResponse getScenarioRun(@PathVariable String runId) {
        BenchmarkRun run = runRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Scenario run not found: " + runId));
        return ScenarioRunResponse.from(run);
    }

    @GetMapping("/benchmarks/{benchmarkId}/entities/{entityName}/sample-id")
    public Map<String, String> sampleEntityId(
            @PathVariable String benchmarkId,
            @PathVariable String entityName,
            @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "false") boolean withChildren) {
        if (withChildren) {
            String id = sampleParentIdWithChildren(benchmarkId, entityName);
            if (id != null) return Map.of("logicalId", id);
        }
        List<String> ids = registryService.sampleLogicalIds(benchmarkId, entityName, 1);
        return ids.isEmpty() ? Map.of() : Map.of("logicalId", ids.get(0));
    }

    private String sampleParentIdWithChildren(String benchmarkId, String parentEntity) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId)
                .orElseThrow(() -> new NoSuchElementException("Benchmark not found: " + benchmarkId));
        LogicalSchema schema = loadSchema(benchmarkId);
        LogicalRelationship rel = schema.relationships().stream()
                .filter(r -> r.parentEntity().equalsIgnoreCase(parentEntity))
                .findFirst()
                .orElse(null);
        if (rel == null) return null;
        BenchmarkDatabase pgDb = benchmark.getDatabases().stream()
                .filter(d -> "postgresql".equalsIgnoreCase(d.getDbName())
                        && d.getStatus() == DatabaseStatus.RUNNING
                        && d.getHostPort() != null)
                .findFirst()
                .orElse(null);
        if (pgDb == null) return null;
        String fk = rel.fkColumnInChild();
        String childTable = "\"" + rel.childEntity().toLowerCase() + "\"";
        String fkCol = "\"" + fk.toLowerCase() + "\"";
        String sql = "SELECT " + fkCol + " FROM " + childTable
                + " WHERE " + fkCol + " IS NOT NULL ORDER BY RANDOM() LIMIT 1";
        try {
            PgConnectionInfo info = PgConnectionInfo.defaultLocal(pgDb.getId(), HOST_ADDRESS, pgDb.getHostPort());
            javax.sql.DataSource ds = pgDataSourceCache.get(info);
            try (java.sql.Connection conn = ds.getConnection();
                 java.sql.PreparedStatement ps = conn.prepareStatement(sql);
                 java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (Exception e) {
            LOG.warn("sample-id-with-children fallback for {}: {}", parentEntity, e.getMessage());
        }
        return null;
    }

    @GetMapping("/benchmarks/{benchmarkId}/relationships")
    public List<Map<String, Object>> listRelationships(@PathVariable String benchmarkId) {
        LogicalSchema schema = loadSchema(benchmarkId);
        return schema.relationships().stream()
                .map(r -> Map.<String, Object>of(
                        "name", r.name() == null ? "" : r.name(),
                        "parentEntity", r.parentEntity(),
                        "childEntity", r.childEntity(),
                        "fkColumnInChild", r.fkColumnInChild() == null ? "" : r.fkColumnInChild(),
                        "cardinality", r.cardinality() == null ? "" : r.cardinality().name()))
                .toList();
    }

    @GetMapping("/benchmarks/{benchmarkId}/scenario-applicability")
    public Map<String, List<String>> getScenarioApplicability(@PathVariable String benchmarkId) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId)
                .orElseThrow(() -> new NoSuchElementException("Benchmark not found: " + benchmarkId));
        Map<String, List<String>> result = new HashMap<>();
        for (ScenarioType type : ScenarioType.values()) {
            List<String> applicableDbIds = new java.util.ArrayList<>();
            for (BenchmarkDatabase db : benchmark.getDatabases()) {
                try {
                    DatabaseEngine engine = DatabaseEngine.of(db.getDbName());
                    if (ScenarioApplicability.isApplicable(type, engine)) {
                        applicableDbIds.add(db.getId());
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            result.put(type.name(), applicableDbIds);
        }
        return result;
    }

    @GetMapping("/scenario-runs/{runId}/results/{resultId}/resource-timeline")
    public List<ResourceSample> getScenarioResourceTimeline(@PathVariable String runId, @PathVariable String resultId) {
        return loadResourceTimeline(runId, resultId, OperationType.SCENARIO);
    }

    @GetMapping("/insert-runs/{runId}/results/{resultId}/resource-timeline")
    public List<ResourceSample> getInsertResourceTimeline(@PathVariable String runId, @PathVariable String resultId) {
        return loadResourceTimeline(runId, resultId, OperationType.INSERT);
    }

    @GetMapping("/read-runs/{runId}/results/{resultId}/resource-timeline")
    public List<ResourceSample> getReadResourceTimeline(@PathVariable String runId, @PathVariable String resultId) {
        return loadResourceTimeline(runId, resultId, OperationType.READ);
    }

    @GetMapping("/delete-runs/{runId}/results/{resultId}/resource-timeline")
    public List<ResourceSample> getDeleteResourceTimeline(@PathVariable String runId, @PathVariable String resultId) {
        return loadResourceTimeline(runId, resultId, OperationType.DELETE);
    }

    private List<ResourceSample> loadResourceTimeline(String runId, String resultId, OperationType expected) {
        BenchmarkRun run = runRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Run not found: " + runId));
        if (run.getOperationType() != expected) {
            throw new IllegalArgumentException("Run " + runId + " is not a " + expected.name() + " run");
        }
        BenchmarkResult result = resultRepository.findById(resultId)
                .orElseThrow(() -> new NoSuchElementException("Result not found: " + resultId));
        if (!result.getRun().getId().equals(runId)) {
            throw new IllegalArgumentException("Result " + resultId + " does not belong to run " + runId);
        }
        String json = result.getResourceSamplesJson();
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, RESOURCE_SAMPLES_TYPE);
        } catch (Exception e) {
            return List.of();
        }
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
