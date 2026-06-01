package com.dbagnets.backend.benchmark.execution;

import com.dbagnets.backend.benchmark.driver.EngineDriver;
import com.dbagnets.backend.benchmark.driver.EngineDriverFactory;
import com.dbagnets.backend.benchmark.driver.ReadContext;
import com.dbagnets.backend.benchmark.model.ReadResultResponse;
import com.dbagnets.backend.benchmark.model.StartReadRunRequest;
import com.dbagnets.backend.benchmark.registry.EntityIdRegistry;
import com.dbagnets.backend.benchmark.registry.EntityIdRegistry.RegistryEntry;
import com.dbagnets.backend.benchmark.schema.EmbeddingMap;
import com.dbagnets.backend.benchmark.schema.EmbeddingMappingLoader;
import com.dbagnets.backend.benchmark.schema.LogicalSchema;
import com.dbagnets.backend.benchmark.schema.LogicalSchemaLoader;
import com.dbagnets.backend.benchmark.timing.LatencyStats;
import com.dbagnets.backend.benchmark.timing.TimedOperation;
import com.dbagnets.backend.entity.Benchmark;
import com.dbagnets.backend.entity.BenchmarkDatabase;
import com.dbagnets.backend.entity.DatabaseEngine;
import com.dbagnets.backend.entity.DatabaseStatus;
import com.dbagnets.backend.repository.BenchmarkRepository;
import com.dbagnets.backend.sse.SseEmitterService;
import com.dbagnets.backend.sse.SseEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
public class ReadRunOrchestrator {

    private static final String HOST_ADDRESS = "127.0.0.1";
    private static final int DEFAULT_SAMPLE_SIZE = 100;

    private final BenchmarkRepository benchmarkRepository;
    private final BenchmarkRunRepository runRepository;
    private final BenchmarkResultRepository resultRepository;
    private final EntityIdRegistry registry;
    private final EngineDriverFactory driverFactory;
    private final LogicalSchemaLoader schemaLoader;
    private final EmbeddingMappingLoader embeddingLoader;
    private final SseEmitterService sse;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    private final ExecutorService asyncExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());

    @Transactional
    public BenchmarkRun startRun(String benchmarkId, StartReadRunRequest request) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId)
                .orElseThrow(() -> new NoSuchElementException("Benchmark not found: " + benchmarkId));
        validateRequest(request);

        BenchmarkRun run = new BenchmarkRun(benchmarkId, OperationType.READ);
        run.setEntityName(request.entityName());
        int sampleSize = orDefault(request.sampleSize(), DEFAULT_SAMPLE_SIZE);
        run.setRecordCount((long) sampleSize);
        run.setConfigJson(serializeQuietly(request));

        for (String databaseId : request.databaseIds()) {
            BenchmarkDatabase db = findDatabase(benchmark, databaseId);
            BenchmarkResult result = new BenchmarkResult(db.getId(), db.getDbName());
            result.setEntityName(request.entityName());
            run.addResult(result);
        }
        runRepository.save(run);
        broadcastRunStatus(benchmarkId, run);

        asyncExecutor.submit(() -> execute(benchmarkId, run.getId(), request));
        return run;
    }

    private void execute(String benchmarkId, String runId, StartReadRunRequest request) {
        try {
            BenchmarkRun run = runRepository.findById(runId).orElseThrow();
            Benchmark benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
            LogicalSchema schema = loadSchema(benchmark);
            run.setStatus(RunStatus.RUNNING);
            runRepository.save(run);
            broadcastRunStatus(benchmarkId, run);

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (BenchmarkResult result : run.getResults()) {
                futures.add(CompletableFuture.runAsync(
                        () -> runForDatabase(benchmarkId, runId, result.getId(), request, schema),
                        asyncExecutor));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

            finalizeRun(benchmarkId, runId);
        } catch (Exception e) {
            log.error("Read run {} failed", runId, e);
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
                                 StartReadRunRequest request,
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
        int sampleSize = orDefault(request.sampleSize(), DEFAULT_SAMPLE_SIZE);
        List<RegistryEntry> targets = registry.sampleEntries(db.getId(), request.entityName(), sampleSize);
        if (targets.isEmpty()) {
            markSkipped(benchmarkId, runId, resultId, "No registered IDs for entity " + request.entityName());
            return;
        }
        EngineDriver driver = driverFactory.driverFor(engine).orElseThrow();
        EmbeddingMap embeddings = EmbeddingMap.from(embeddingLoader.parse(db.getEmbeddingMappings()));

        markStarted(benchmarkId, runId, resultId);
        try {
            ReadContext ctx = new ReadContext(
                    benchmarkId,
                    db.getId(),
                    db.getDbName(),
                    db.getDbVersion(),
                    HOST_ADDRESS,
                    db.getHostPort(),
                    schema,
                    embeddings,
                    request.entityName(),
                    targets,
                    Boolean.TRUE.equals(request.includeChildren()));
            TimedOperation timed = driver.read(ctx);
            persistSuccess(benchmarkId, runId, resultId, timed);
        } catch (Exception ex) {
            log.error("Read failed for db {} run {}: {}", db.getDbName(), runId, ex.getMessage(), ex);
            markFailed(benchmarkId, runId, resultId, ex.getMessage());
        }
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
            LatencyStats stats = LatencyStats.from(timed.sampleDbTimeNs());
            r.setStatus(RunStatus.SUCCESS);
            r.setFinishedAt(Instant.now());
            r.setDbTimeNs(timed.dbTimeNs());
            r.setWireTimeNs(timed.wireTimeNs());
            r.setOverheadNs(timed.overheadNs());
            r.setRowsAffected(timed.rowsAffected());
            r.setP50DbTimeNs(stats.p50Ns());
            r.setP95DbTimeNs(stats.p95Ns());
            r.setP99DbTimeNs(stats.p99Ns());
            r.setMeanDbTimeNs(stats.meanNs());
            r.setSamplesRecorded(stats.sampleCount());
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

    private void validateRequest(StartReadRunRequest request) {
        if (request.entityName() == null || request.entityName().isBlank()) {
            throw new IllegalArgumentException("entityName is required");
        }
        if (request.databaseIds() == null || request.databaseIds().isEmpty()) {
            throw new IllegalArgumentException("At least one database must be selected");
        }
    }

    private void broadcastRunStatus(String benchmarkId, BenchmarkRun run) {
        sse.sendEvent(benchmarkId, SseEvents.EVENT_READ_RUN_STATUS,
                Map.of("runId", run.getId(), "status", run.getStatus().name()));
    }

    private void broadcastResult(String benchmarkId, String runId, BenchmarkResult result) {
        sse.sendEvent(benchmarkId, SseEvents.EVENT_READ_RESULT_STATUS,
                Map.of("runId", runId, "result", ReadResultResponse.from(result)));
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
