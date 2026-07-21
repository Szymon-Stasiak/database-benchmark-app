package com.dbagnets.backend.benchmark.run.application.delete;

import com.dbagnets.backend.benchmark.run.persistence.BenchmarkRun;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkResult;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkRunRepository;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkResultRepository;
import com.dbagnets.backend.benchmark.run.persistence.OperationType;
import com.dbagnets.backend.benchmark.run.persistence.RunStatus;
import com.dbagnets.backend.engine.driver.DeleteContext;
import com.dbagnets.backend.engine.driver.EngineDriver;
import com.dbagnets.backend.engine.driver.EngineDriverFactory;
import com.dbagnets.backend.benchmark.run.api.dto.DeleteResultResponse;
import com.dbagnets.backend.benchmark.run.api.dto.PreparedRunResponse;
import com.dbagnets.backend.benchmark.run.api.dto.StartDeleteRunRequest;
import com.dbagnets.backend.benchmark.run.internal.CascadePreviewService;
import com.dbagnets.backend.benchmark.run.internal.RunPreview;
import com.dbagnets.backend.engine.registry.EntityIdRegistry;
import com.dbagnets.backend.engine.registry.EntityIdRegistry.RegistryEntry;
import com.dbagnets.backend.engine.resource.ContainerStatsCollector;
import com.dbagnets.backend.engine.resource.ResourceMetricsSummary;
import com.dbagnets.backend.engine.schema.EmbeddingMap;
import com.dbagnets.backend.engine.schema.EmbeddingMappingLoader;
import com.dbagnets.backend.engine.schema.LogicalSchema;
import com.dbagnets.backend.engine.schema.LogicalSchemaLoader;
import com.dbagnets.backend.benchmark.result.application.DataSizeProbe;
import com.dbagnets.backend.engine.timing.LatencyStats;
import com.dbagnets.backend.engine.timing.TimedOperation;
import com.dbagnets.backend.shared.entity.Benchmark;
import com.dbagnets.backend.shared.entity.BenchmarkDatabase;
import com.dbagnets.backend.domain.DatabaseEngine;
import com.dbagnets.backend.domain.DatabaseStatus;
import com.dbagnets.backend.infrastructure.persistence.BenchmarkRepository;
import com.dbagnets.backend.shared.event.BenchmarkEventPort;
import com.dbagnets.backend.infrastructure.sse.SseEvents;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeleteRunOrchestrator {

    private static final int DEFAULT_SAMPLE_SIZE = 100;

    @Value("${app.container-host}")
    private String hostAddress;

    private final BenchmarkRepository benchmarkRepository;
    private final BenchmarkRunRepository runRepository;
    private final BenchmarkResultRepository resultRepository;
    private final EntityIdRegistry registry;
    private final EngineDriverFactory driverFactory;
    private final LogicalSchemaLoader schemaLoader;
    private final EmbeddingMappingLoader embeddingLoader;
    private final DataSizeProbe dataSizeProbe;
    private final CascadePreviewService cascadePreviewService;
    private final BenchmarkEventPort sse;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ContainerStatsCollector statsCollector;

    private final ExecutorService asyncExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());

    @Transactional
    public PreparedRunResponse prepareRun(String benchmarkId, StartDeleteRunRequest request) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId)
                .orElseThrow(() -> new NoSuchElementException("Benchmark not found: " + benchmarkId));
        validateRequest(request);
        LogicalSchema schema = loadSchema(benchmark);

        int sampleSize = orDefault(request.sampleSize(), DEFAULT_SAMPLE_SIZE);
        List<String> selectedIds = registry.selectLogicalIds(
                benchmarkId, request.entityName(), sampleSize, request.strategyOrDefault());
        RunPreview preview = cascadePreviewService.build(
                benchmarkId, schema, request.entityName(), selectedIds.size(),
                Boolean.TRUE.equals(request.includeChildren()));

        BenchmarkRun run = new BenchmarkRun(benchmarkId, OperationType.DELETE);
        run.setEntityName(request.entityName());
        run.setRecordCount((long) selectedIds.size());
        run.setConfigJson(serializeQuietly(request));
        run.setSelectedIdsJson(serializeQuietly(selectedIds));
        run.setCascadePreviewJson(serializeQuietly(preview));

        for (String databaseId : request.databaseIds()) {
            BenchmarkDatabase db = findDatabase(benchmark, databaseId);
            BenchmarkResult result = new BenchmarkResult(db.getId(), db.getDbName());
            result.setEntityName(request.entityName());
            run.addResult(result);
        }
        runRepository.save(run);
        broadcastRunStatus(benchmarkId, run);
        sse.sendEvent(benchmarkId, SseEvents.EVENT_DELETE_RUN_PREPARED,
                Map.of("runId", run.getId(), "preview", preview));

        return new PreparedRunResponse(
                run.getId(),
                benchmarkId,
                OperationType.DELETE.name(),
                request.entityName(),
                run.getStatus().name(),
                preview);
    }

    public void confirmRun(String runId) {
        BenchmarkRun run = runRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Delete run not found: " + runId));
        if (run.getStatus() != RunStatus.PENDING) {
            throw new IllegalStateException("Run " + runId + " is not in PENDING state (status=" + run.getStatus() + ")");
        }
        if (run.getOperationType() != OperationType.DELETE) {
            throw new IllegalArgumentException("Run " + runId + " is not a DELETE run");
        }
        if (run.getSelectedIdsJson() == null) {
            throw new IllegalStateException("Run " + runId + " has no selected IDs — prepare first");
        }
        asyncExecutor.submit(() -> execute(run.getBenchmarkId(), runId));
    }

    public BenchmarkRun startRun(String benchmarkId, StartDeleteRunRequest request) {
        PreparedRunResponse prepared = prepareRun(benchmarkId, request);
        confirmRun(prepared.runId());
        return runRepository.findById(prepared.runId()).orElseThrow();
    }

    private void execute(String benchmarkId, String runId) {
        try {
            BenchmarkRun run = runRepository.findById(runId).orElseThrow();
            Benchmark benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
            LogicalSchema schema = loadSchema(benchmark);
            List<String> selectedIds = parseSelectedIds(run.getSelectedIdsJson());
            com.dbagnets.backend.engine.driver.DeletionMode deletionMode = parseDeletionMode(run.getConfigJson());
            com.dbagnets.backend.engine.driver.InsertMode mode = parseMode(run.getConfigJson());
            String entityName = run.getEntityName();

            run.setStatus(RunStatus.RUNNING);
            runRepository.save(run);
            broadcastRunStatus(benchmarkId, run);

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (BenchmarkResult result : run.getResults()) {
                futures.add(CompletableFuture.runAsync(
                        () -> runForDatabase(benchmarkId, runId, result.getId(),
                                entityName, selectedIds, deletionMode, mode, schema),
                        asyncExecutor));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

            finalizeRun(benchmarkId, runId);
        } catch (Exception e) {
            log.error("Delete run {} failed", runId, e);
            transactionTemplate.executeWithoutResult(s -> {
                BenchmarkRun run = runRepository.findById(runId).orElseThrow();
                run.setStatus(RunStatus.FAILED);
                run.setFinishedAt(Instant.now());
                runRepository.save(run);
                broadcastRunStatus(benchmarkId, run);
            });
        }
    }

    private void runForDatabase(String benchmarkId,
                                 String runId,
                                 String resultId,
                                 String entityName,
                                 List<String> selectedLogicalIds,
                                 com.dbagnets.backend.engine.driver.DeletionMode deletionMode,
                                 com.dbagnets.backend.engine.driver.InsertMode mode,
                                 LogicalSchema schema) {
        BenchmarkDatabase db = transactionTemplate.execute(s ->
                benchmarkRepository.findById(benchmarkId).orElseThrow()
                        .getDatabases().stream()
                        .filter(x -> x.getId().equals(resultIdToDatabaseId(resultId)))
                        .findFirst()
                        .orElseThrow());
        DatabaseEngine engine;
        try {
            engine = DatabaseEngine.of(db.getDbName());
        } catch (IllegalArgumentException e) {
            markSkipped(benchmarkId, runId, resultId, "Unknown engine: " + db.getDbName());
            return;
        }
        if (!driverFactory.supports(engine) || db.getStatus() != DatabaseStatus.RUNNING || db.getHostPort() == null) {
            markSkipped(benchmarkId, runId, resultId, "Engine not supported or container not running");
            return;
        }
        List<RegistryEntry> targets = registry.lookupEntries(db.getId(), entityName, selectedLogicalIds);
        if (targets.isEmpty()) {
            markSkipped(benchmarkId, runId, resultId, "No matching IDs in registry for " + db.getDbName());
            return;
        }
        EngineDriver driver = driverFactory.driverFor(engine).orElseThrow();
        EmbeddingMap embeddings = EmbeddingMap.from(embeddingLoader.parse(db.getEmbeddingMappings()));

        warmup(driver, schema, embeddings, benchmarkId, db, entityName, targets);
        Long sizeBefore = safeProbe(db);
        markStarted(benchmarkId, runId, resultId, sizeBefore);
        ContainerStatsCollector.Handle statsHandle = statsCollector.start(
                benchmarkId, runId, resultId, db.getId(), db.getDbName(), db.getContainerId(), "delete");
        try {
            DeleteContext ctx = new DeleteContext(
                    benchmarkId,
                    db.getId(),
                    db.getDbName(),
                    db.getDbVersion(),
                    hostAddress,
                    db.getHostPort(),
                    schema,
                    embeddings,
                    entityName,
                    targets,
                    deletionMode,
                    mode);
            TimedOperation timed = driver.delete(ctx);
            List<String> deletedLogicalIds = targets.stream().map(RegistryEntry::logicalId).toList();
            registry.deleteByLogicalIds(db.getId(), entityName, deletedLogicalIds);
            for (var cascadeEntry : timed.cascadeDeletedByEntity().entrySet()) {
                if (!cascadeEntry.getValue().isEmpty()) {
                    registry.deleteByPhysicalIds(db.getId(), cascadeEntry.getKey(), cascadeEntry.getValue());
                }
            }
            dataSizeProbe.invalidate(db.getId());
            sse.sendEvent(benchmarkId, SseEvents.EVENT_DATABASE_SIZE_DIRTY,
                    Map.of("databaseId", db.getId(), "entityName", entityName));
            Long sizeAfter = safeProbe(db);
            persistSuccess(benchmarkId, runId, resultId, timed, sizeAfter);
        } catch (Exception ex) {
            log.error("Delete failed for db {} run {}: {}", db.getDbName(), runId, ex.getMessage(), ex);
            markFailed(benchmarkId, runId, resultId, ex.getMessage());
        } finally {
            ResourceMetricsSummary summary = statsCollector.stop(statsHandle);
            persistResourceSummary(benchmarkId, runId, resultId, summary);
        }
    }

    private void persistResourceSummary(String benchmarkId, String runId, String resultId, ResourceMetricsSummary summary) {
        if (summary == null || summary.sampleCount() == null || summary.sampleCount() == 0) return;
        transactionTemplate.executeWithoutResult(s -> {
            BenchmarkResult r = resultRepository.findById(resultId).orElseThrow();
            r.setCpuPercentMax(summary.cpuPercentMax());
            r.setCpuPercentMean(summary.cpuPercentMean());
            r.setCpuPercentP95(summary.cpuPercentP95());
            r.setMemoryBytesMax(summary.memoryBytesMax());
            r.setMemoryBytesMean(summary.memoryBytesMean());
            r.setMemoryBytesP95(summary.memoryBytesP95());
            r.setResourceSampleCount(summary.sampleCount());
            r.setResourceSamplesJson(summary.samplesJson());
            resultRepository.save(r);
            broadcastResult(benchmarkId, runId, r);
        });
    }

    private void warmup(EngineDriver driver,
                         LogicalSchema schema,
                         EmbeddingMap embeddings,
                         String benchmarkId,
                         BenchmarkDatabase db,
                         String entityName,
                         List<RegistryEntry> targets) {
        if (targets.isEmpty()) return;
        try {
            com.dbagnets.backend.engine.driver.ReadContext warmCtx =
                    new com.dbagnets.backend.engine.driver.ReadContext(
                            benchmarkId,
                            db.getId(),
                            db.getDbName(),
                            db.getDbVersion(),
                            hostAddress,
                            db.getHostPort(),
                            schema,
                            embeddings,
                            entityName,
                            targets.subList(0, 1),
                            false);
            driver.read(warmCtx);
        } catch (Exception ignored) {
            // warmup is best-effort — first measurement may carry cold-cache overhead, that's OK
        }
    }

    private Long safeProbe(BenchmarkDatabase db) {
        try {
            return dataSizeProbe.sizeOf(db, hostAddress);
        } catch (Exception ex) {
            log.debug("Size probe failed for {}: {}", db.getDbName(), ex.getMessage());
            return null;
        }
    }

    private void markStarted(String benchmarkId, String runId, String resultId, Long sizeBefore) {
        transactionTemplate.executeWithoutResult(s -> {
            BenchmarkResult r = resultRepository.findById(resultId).orElseThrow();
            r.setStatus(RunStatus.RUNNING);
            r.setStartedAt(Instant.now());
            r.setDataSizeBefore(sizeBefore);
            resultRepository.save(r);
            broadcastResult(benchmarkId, runId, r);
        });
    }

    private void persistSuccess(String benchmarkId, String runId, String resultId, TimedOperation timed, Long sizeAfter) {
        transactionTemplate.executeWithoutResult(s -> {
            BenchmarkResult r = resultRepository.findById(resultId).orElseThrow();
            LatencyStats stats = LatencyStats.from(timed.sampleDbTimeNs());
            r.setStatus(RunStatus.SUCCESS);
            r.setFinishedAt(Instant.now());
            r.setDbTimeNs(timed.dbTimeNs());
            r.setWireTimeNs(timed.wireTimeNs());
            r.setOverheadNs(timed.overheadNs());
            r.setRowsAffected(timed.rowsAffected());
            var breakdown = new java.util.LinkedHashMap<String, Integer>();
            long cascadeTotal = 0L;
            for (var e : timed.cascadeDeletedByEntity().entrySet()) {
                int n = e.getValue().size();
                breakdown.put(e.getKey(), n);
                cascadeTotal += n;
            }
            r.setCascadeRowsAffected(cascadeTotal);
            r.setCascadeBreakdownJson(breakdown.isEmpty() ? null : serializeQuietly(breakdown));
            r.setP50DbTimeNs(stats.p50Ns());
            r.setP95DbTimeNs(stats.p95Ns());
            r.setP99DbTimeNs(stats.p99Ns());
            r.setMeanDbTimeNs(stats.meanNs());
            r.setSamplesRecorded(stats.sampleCount());
            r.setDataSizeAfter(sizeAfter);
            resultRepository.save(r);
            broadcastResult(benchmarkId, runId, r);
        });
    }

    private void markFailed(String benchmarkId, String runId, String resultId, String message) {
        transactionTemplate.executeWithoutResult(s -> {
            BenchmarkResult r = resultRepository.findById(resultId).orElseThrow();
            r.setStatus(RunStatus.FAILED);
            r.setFinishedAt(Instant.now());
            r.setErrorMessage(message);
            resultRepository.save(r);
            broadcastResult(benchmarkId, runId, r);
        });
    }

    private void markSkipped(String benchmarkId, String runId, String resultId, String reason) {
        transactionTemplate.executeWithoutResult(s -> {
            BenchmarkResult r = resultRepository.findById(resultId).orElseThrow();
            r.setStatus(RunStatus.SKIPPED);
            r.setStartedAt(Instant.now());
            r.setFinishedAt(Instant.now());
            r.setErrorMessage(reason);
            resultRepository.save(r);
            broadcastResult(benchmarkId, runId, r);
        });
    }

    private void finalizeRun(String benchmarkId, String runId) {
        transactionTemplate.executeWithoutResult(s -> {
            BenchmarkRun run = runRepository.findById(runId).orElseThrow();
            run.setFinishedAt(Instant.now());
            run.setStatus(aggregateStatus(run.getResults()));
            runRepository.save(run);
            broadcastRunStatus(benchmarkId, run);
        });
    }

    private RunStatus aggregateStatus(List<BenchmarkResult> results) {
        if (results.isEmpty()) return RunStatus.FAILED;
        boolean anySuccess = false;
        boolean anyFailed = false;
        for (BenchmarkResult r : results) {
            if (r.getStatus() == RunStatus.SUCCESS) anySuccess = true;
            if (r.getStatus() == RunStatus.FAILED) anyFailed = true;
        }
        if (anySuccess && anyFailed) return RunStatus.PARTIAL;
        if (anyFailed) return RunStatus.FAILED;
        return RunStatus.SUCCESS;
    }

    private LogicalSchema loadSchema(Benchmark benchmark) {
        if (benchmark.getLogicalSchema() == null) {
            throw new IllegalStateException("Benchmark " + benchmark.getId() + " has no logical schema");
        }
        return schemaLoader.parse(benchmark.getLogicalSchema());
    }

    private BenchmarkDatabase findDatabase(Benchmark benchmark, String databaseId) {
        return benchmark.getDatabases().stream()
                .filter(d -> d.getId().equals(databaseId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Database not in benchmark: " + databaseId));
    }

    private String resultIdToDatabaseId(String resultId) {
        BenchmarkResult result = resultRepository.findById(resultId).orElseThrow();
        return result.getDatabaseId();
    }

    private void validateRequest(StartDeleteRunRequest request) {
        if (request.entityName() == null || request.entityName().isBlank()) {
            throw new IllegalArgumentException("entityName is required");
        }
        if (request.databaseIds() == null || request.databaseIds().isEmpty()) {
            throw new IllegalArgumentException("At least one database must be selected");
        }
    }

    private void broadcastRunStatus(String benchmarkId, BenchmarkRun run) {
        sse.sendEvent(benchmarkId, SseEvents.EVENT_DELETE_RUN_STATUS,
                Map.of("runId", run.getId(), "status", run.getStatus().name()));
    }

    private void broadcastResult(String benchmarkId, String runId, BenchmarkResult result) {
        sse.sendEvent(benchmarkId, SseEvents.EVENT_DELETE_RESULT_STATUS,
                Map.of("runId", runId, "result", DeleteResultResponse.from(result)));
    }

    private String serializeQuietly(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> parseSelectedIds(String json) {
        if (json == null) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Cannot parse selectedIdsJson: {}", e.getMessage());
            return List.of();
        }
    }

    private boolean parseIncludeChildren(String configJson) {
        if (configJson == null) return false;
        try {
            StartDeleteRunRequest req = objectMapper.readValue(configJson, StartDeleteRunRequest.class);
            return Boolean.TRUE.equals(req.includeChildren());
        } catch (Exception e) {
            return false;
        }
    }

    private com.dbagnets.backend.engine.driver.DeletionMode parseDeletionMode(String configJson) {
        if (configJson == null) return com.dbagnets.backend.engine.driver.DeletionMode.NATIVE;
        try {
            StartDeleteRunRequest req = objectMapper.readValue(configJson, StartDeleteRunRequest.class);
            return req.deletionModeOrDefault();
        } catch (Exception e) {
            return com.dbagnets.backend.engine.driver.DeletionMode.NATIVE;
        }
    }

    private com.dbagnets.backend.engine.driver.InsertMode parseMode(String configJson) {
        if (configJson == null) return com.dbagnets.backend.engine.driver.InsertMode.SINGLE;
        try {
            StartDeleteRunRequest req = objectMapper.readValue(configJson, StartDeleteRunRequest.class);
            return req.modeOrDefault();
        } catch (Exception e) {
            return com.dbagnets.backend.engine.driver.InsertMode.SINGLE;
        }
    }

    private int orDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
