package com.dbagnets.backend.benchmark.run.application.insert;

import com.dbagnets.backend.benchmark.run.persistence.BenchmarkRun;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkResult;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkRunRepository;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkResultRepository;
import com.dbagnets.backend.benchmark.run.persistence.OperationType;
import com.dbagnets.backend.benchmark.run.persistence.RunStatus;
import com.dbagnets.backend.engine.cascade.CascadePlan;
import com.dbagnets.backend.engine.cascade.CascadePlanner;
import com.dbagnets.backend.engine.cascade.LeafChoice;
import com.dbagnets.backend.engine.datagen.GeneratedRow;
import com.dbagnets.backend.engine.datagen.PrimaryKeyVault;
import com.dbagnets.backend.engine.datagen.RecordBuilder;
import com.dbagnets.backend.engine.driver.BatchProgress;
import com.dbagnets.backend.engine.driver.EngineDriver;
import com.dbagnets.backend.engine.driver.EngineDriverFactory;
import com.dbagnets.backend.engine.driver.InsertContext;
import com.dbagnets.backend.engine.driver.InsertMode;
import com.dbagnets.backend.benchmark.run.api.dto.BatchProgressEvent;
import com.dbagnets.backend.benchmark.run.api.dto.EdgeRatioDto;
import com.dbagnets.backend.benchmark.run.api.dto.EntityCascadeChoiceDto;
import com.dbagnets.backend.benchmark.run.api.dto.InsertResultResponse;
import com.dbagnets.backend.benchmark.run.api.dto.StartInsertRunRequest;
import com.dbagnets.backend.engine.registry.EntityIdRegistry;
import com.dbagnets.backend.engine.resource.ContainerStatsCollector;
import com.dbagnets.backend.engine.resource.ResourceMetricsSummary;
import com.dbagnets.backend.benchmark.result.application.DataSizeProbe;
import com.dbagnets.backend.engine.schema.EmbeddingMap;
import com.dbagnets.backend.engine.schema.EmbeddingMappingLoader;
import com.dbagnets.backend.engine.schema.LogicalSchema;
import com.dbagnets.backend.engine.schema.LogicalSchemaLoader;
import com.dbagnets.backend.engine.timing.RecordedId;
import com.dbagnets.backend.engine.timing.TimedOperation;
import com.dbagnets.backend.shared.entity.Benchmark;
import com.dbagnets.backend.shared.entity.BenchmarkDatabase;
import com.dbagnets.backend.domain.DatabaseEngine;
import com.dbagnets.backend.domain.DatabaseStatus;
import com.dbagnets.backend.infrastructure.persistence.BenchmarkRepository;
import com.dbagnets.backend.shared.event.BenchmarkEventPort;
import com.dbagnets.backend.infrastructure.sse.SseEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InsertRunOrchestrator {

    private static final String HOST_ADDRESS = "127.0.0.1";
    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int DEFAULT_WORKER_COUNT = 4;

    private final BenchmarkRepository benchmarkRepository;
    private final BenchmarkRunRepository runRepository;
    private final BenchmarkResultRepository resultRepository;
    private final EntityIdRegistry registry;
    private final CascadePlanner planner;
    private final RecordBuilder recordBuilder;
    private final EngineDriverFactory driverFactory;
    private final LogicalSchemaLoader schemaLoader;
    private final EmbeddingMappingLoader embeddingLoader;
    private final BenchmarkEventPort sse;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final DataSizeProbe dataSizeProbe;
    private final ContainerStatsCollector statsCollector;
    private final InsertProgressTracker progressTracker;

    private final ExecutorService asyncExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());

    @Transactional
    public BenchmarkRun startRun(String benchmarkId, StartInsertRunRequest request) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId)
                .orElseThrow(() -> new NoSuchElementException("Benchmark not found: " + benchmarkId));
        validateRequest(request);

        LogicalSchema schema = loadSchema(benchmark);
        CascadePlan plan = planFor(schema, request);

        BenchmarkRun run = new BenchmarkRun(benchmarkId, OperationType.INSERT);
        run.setMode(request.mode().name());
        run.setBatchSize(orDefault(request.batchSize(), DEFAULT_BATCH_SIZE));
        run.setWorkerCount(orDefault(request.workerCount(), DEFAULT_WORKER_COUNT));
        run.setEntityName(primaryEntityName(request));
        run.setRecordCount(totalLeafRecordCount(request));
        run.setConfigJson(serializeQuietly(request));
        run.setCascadeJson(serializeQuietly(plan));

        for (String databaseId : request.databaseIds()) {
            BenchmarkDatabase db = findDatabase(benchmark, databaseId);
            BenchmarkResult result = new BenchmarkResult(db.getId(), db.getDbName());
            result.setEntityName(run.getEntityName());
            run.addResult(result);
        }
        runRepository.save(run);
        broadcastRunStatus(benchmarkId, run);

        asyncExecutor.submit(() -> execute(benchmarkId, run.getId(), request));
        return run;
    }

    private void execute(String benchmarkId, String runId, StartInsertRunRequest request) {
        try {
            BenchmarkRun run = runRepository.findById(runId).orElseThrow();
            Benchmark benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
            LogicalSchema schema = loadSchema(benchmark);
            CascadePlan plan = planFor(schema, request);
            run.setStatus(RunStatus.RUNNING);
            runRepository.save(run);
            broadcastRunStatus(benchmarkId, run);

            PrimaryKeyVault vault = new PrimaryKeyVault();
            Map<String, List<GeneratedRow>> rowsByEntity = recordBuilder.generateAll(schema, plan, vault);

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (BenchmarkResult result : run.getResults()) {
                futures.add(CompletableFuture.runAsync(
                        () -> runForDatabase(benchmarkId, runId, result.getId(), request, schema, plan, rowsByEntity),
                        asyncExecutor));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

            finalizeRun(benchmarkId, runId);
        } catch (Exception e) {
            log.error("Insert run {} failed", runId, e);
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
                                 StartInsertRunRequest request,
                                 LogicalSchema schema,
                                 CascadePlan plan,
                                 Map<String, List<GeneratedRow>> rowsByEntity) {
        BenchmarkDatabase db = transactionTemplate.execute(s ->
                benchmarkRepository.findById(benchmarkId).orElseThrow()
                        .getDatabases().stream()
                        .filter(x -> x.getId().equals(resultIdToDatabaseId(runId, resultId)))
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
        EngineDriver driver = driverFactory.driverFor(engine).orElseThrow();
        EmbeddingMap embeddings = EmbeddingMap.from(embeddingLoader.parse(db.getEmbeddingMappings()));

        markStarted(benchmarkId, runId, resultId);
        ContainerStatsCollector.Handle statsHandle = statsCollector.start(
                benchmarkId, runId, resultId, db.getId(), db.getDbName(), db.getContainerId(), "insert");
        BatchProgress progress = new BatchProgress() {
            @Override
            public void onBatch(String entityName, int idx, int total, long done, long all) {
                BatchProgressEvent event = new BatchProgressEvent(
                        runId, resultId, db.getId(), entityName, idx, total, done, all);
                progressTracker.record(event);
                sse.sendEvent(benchmarkId, SseEvents.EVENT_INSERT_BATCH_PROGRESS, event);
            }

            @Override
            public void onEntityFinished(String entityName) {
                dataSizeProbe.invalidate(db.getId());
                sse.sendEvent(
                        benchmarkId,
                        SseEvents.EVENT_DATABASE_SIZE_DIRTY,
                        Map.of("databaseId", db.getId(), "entityName", entityName));
            }
        };
        try {
            InsertContext ctx = new InsertContext(
                    benchmarkId,
                    db.getId(),
                    db.getDbName(),
                    db.getDbVersion(),
                    HOST_ADDRESS,
                    db.getHostPort(),
                    schema,
                    embeddings,
                    plan,
                    rowsByEntity,
                    request.mode() == null ? InsertMode.BATCH : request.mode(),
                    orDefault(request.batchSize(), DEFAULT_BATCH_SIZE),
                    progress);
            TimedOperation timed = driver.insert(ctx);
            persistSuccess(benchmarkId, runId, resultId, timed);
        } catch (Exception ex) {
            log.error("Insert failed for db {} run {}: {}", db.getDbName(), runId, ex.getMessage(), ex);
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

    private void markStarted(String benchmarkId, String runId, String resultId) {
        transactionTemplate.executeWithoutResult(s -> {
            BenchmarkResult r = resultRepository.findById(resultId).orElseThrow();
            r.setStatus(RunStatus.RUNNING);
            r.setStartedAt(Instant.now());
            resultRepository.save(r);
            broadcastResult(benchmarkId, runId, r);
        });
    }

    private void persistSuccess(String benchmarkId, String runId, String resultId, TimedOperation timed) {
        transactionTemplate.executeWithoutResult(s -> {
            BenchmarkResult r = resultRepository.findById(resultId).orElseThrow();
            r.setStatus(RunStatus.SUCCESS);
            r.setFinishedAt(Instant.now());
            r.setDbTimeNs(timed.dbTimeNs());
            r.setWireTimeNs(timed.wireTimeNs());
            r.setOverheadNs(timed.overheadNs());
            r.setRowsAffected(timed.rowsAffected());
            r.setConflictsSkipped(timed.conflictsSkipped());
            resultRepository.save(r);

            if (!timed.recordedIds().isEmpty()) {
                Map<String, List<EntityIdRegistry.RegistryEntry>> byEntity = new HashMap<>();
                for (RecordedId id : timed.recordedIds()) {
                    byEntity.computeIfAbsent(id.entityName(), k -> new ArrayList<>())
                            .add(new EntityIdRegistry.RegistryEntry(id.logicalId(), id.physicalId()));
                }
                String benchmarkIdLocal = r.getRun().getBenchmarkId();
                String databaseIdLocal = r.getDatabaseId();
                for (Map.Entry<String, List<EntityIdRegistry.RegistryEntry>> entry : byEntity.entrySet()) {
                    registry.recordAll(benchmarkIdLocal, databaseIdLocal, entry.getKey(), entry.getValue());
                }
            }
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

    private CascadePlan planFor(LogicalSchema schema, StartInsertRunRequest request) {
        List<LeafChoice> leaves = request.entities().stream()
                .map(c -> new LeafChoice(c.entityName(), c.recordCount()))
                .toList();
        Map<String, Double> overrides = new HashMap<>();
        for (EntityCascadeChoiceDto entity : request.entities()) {
            for (EdgeRatioDto edge : entity.edgeRatios()) {
                overrides.put(edge.parentEntity() + "_" + edge.childEntity(), edge.ratio());
            }
        }
        return planner.plan(schema, leaves, overrides);
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

    private String resultIdToDatabaseId(String runId, String resultId) {
        BenchmarkResult result = resultRepository.findById(resultId).orElseThrow();
        return result.getDatabaseId();
    }

    private String primaryEntityName(StartInsertRunRequest request) {
        return request.entities().isEmpty() ? null : request.entities().get(0).entityName();
    }

    private long totalLeafRecordCount(StartInsertRunRequest request) {
        return request.entities().stream().mapToLong(EntityCascadeChoiceDto::recordCount).sum();
    }

    private void validateRequest(StartInsertRunRequest request) {
        if (request.entities() == null || request.entities().isEmpty()) {
            throw new IllegalArgumentException("At least one leaf entity is required");
        }
        if (request.databaseIds() == null || request.databaseIds().isEmpty()) {
            throw new IllegalArgumentException("At least one database must be selected");
        }
        if (request.mode() == null) {
            throw new IllegalArgumentException("mode is required");
        }
    }

    private void broadcastRunStatus(String benchmarkId, BenchmarkRun run) {
        sse.sendEvent(benchmarkId, SseEvents.EVENT_INSERT_RUN_STATUS,
                Map.of("runId", run.getId(), "status", run.getStatus().name()));
    }

    private void broadcastResult(String benchmarkId, String runId, BenchmarkResult result) {
        sse.sendEvent(benchmarkId, SseEvents.EVENT_INSERT_RESULT_STATUS,
                Map.of("runId", runId, "result", InsertResultResponse.from(result)));
    }

    private String serializeQuietly(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return null;
        }
    }

    private int orDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
