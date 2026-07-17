package com.dbagnets.backend.benchmark.run.application.scenario;

import com.dbagnets.backend.benchmark.run.persistence.BenchmarkRun;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkResult;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkRunRepository;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkResultRepository;
import com.dbagnets.backend.benchmark.run.persistence.OperationType;
import com.dbagnets.backend.benchmark.run.persistence.RunStatus;
import com.dbagnets.backend.engine.driver.EngineDriver;
import com.dbagnets.backend.engine.driver.EngineDriverFactory;
import com.dbagnets.backend.benchmark.run.api.dto.PreparedScenarioRunResponse;
import com.dbagnets.backend.benchmark.run.api.dto.ScenarioResultResponse;
import com.dbagnets.backend.benchmark.run.api.dto.ScenarioRunResponse;
import com.dbagnets.backend.benchmark.run.api.dto.StartScenarioRunRequest;
import com.dbagnets.backend.engine.resource.ContainerStatsCollector;
import com.dbagnets.backend.engine.resource.ResourceMetricsSummary;
import com.dbagnets.backend.engine.scenario.KnnParams;
import com.dbagnets.backend.engine.scenario.ScenarioApplicability;
import com.dbagnets.backend.engine.scenario.ScenarioContext;
import com.dbagnets.backend.engine.scenario.ScenarioParams;
import com.dbagnets.backend.engine.scenario.ScenarioResult;
import com.dbagnets.backend.engine.scenario.ScenarioType;
import com.dbagnets.backend.engine.scenario.TraversalParams;
import com.dbagnets.backend.engine.schema.EmbeddingMap;
import com.dbagnets.backend.engine.schema.EmbeddingMappingLoader;
import com.dbagnets.backend.engine.schema.LogicalSchema;
import com.dbagnets.backend.engine.schema.LogicalSchemaLoader;
import com.dbagnets.backend.engine.timing.LatencyStats;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScenarioRunOrchestrator {

    private static final String HOST_ADDRESS = "127.0.0.1";
    public static final String CONSISTENCY_MATCH = "MATCH";
    public static final String CONSISTENCY_MISMATCH = "MISMATCH";
    public static final String CONSISTENCY_INCOMPLETE = "INCOMPLETE";

    private final BenchmarkRepository benchmarkRepository;
    private final BenchmarkRunRepository runRepository;
    private final BenchmarkResultRepository resultRepository;
    private final EngineDriverFactory driverFactory;
    private final LogicalSchemaLoader schemaLoader;
    private final EmbeddingMappingLoader embeddingLoader;
    private final BenchmarkEventPort sse;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ContainerStatsCollector statsCollector;

    private final ExecutorService asyncExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());

    @Transactional
    public PreparedScenarioRunResponse prepareRun(String benchmarkId, StartScenarioRunRequest request) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId)
                .orElseThrow(() -> new NoSuchElementException("Benchmark not found: " + benchmarkId));
        validateRequest(request);

        ScenarioParams params = request.params();
        ScenarioType type = params.type();
        int iterations = request.iterationsOrDefault();

        BenchmarkRun run = new BenchmarkRun(benchmarkId, OperationType.SCENARIO);
        run.setScenarioType(type.name());
        run.setIterations(iterations);
        run.setConfigJson(serializeQuietly(request));

        List<PreparedScenarioRunResponse.Applicability> applicability = new ArrayList<>();
        for (String databaseId : request.databaseIds()) {
            BenchmarkDatabase db = findDatabase(benchmark, databaseId);
            BenchmarkResult result = new BenchmarkResult(db.getId(), db.getDbName());
            result.setScenarioType(type.name());
            run.addResult(result);
            boolean applicable = isEngineApplicable(type, db);
            applicability.add(new PreparedScenarioRunResponse.Applicability(
                    db.getId(),
                    db.getDbName(),
                    applicable,
                    applicable ? null : "Scenario " + type + " not applicable for " + db.getDbName()));
        }
        runRepository.save(run);
        broadcastRunStatus(benchmarkId, run);
        sse.sendEvent(benchmarkId, SseEvents.EVENT_SCENARIO_RUN_PREPARED,
                Map.of("runId", run.getId(), "applicability", applicability));

        return new PreparedScenarioRunResponse(
                run.getId(),
                benchmarkId,
                type.name(),
                run.getStatus().name(),
                applicability);
    }

    public void confirmRun(String runId) {
        BenchmarkRun run = runRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Scenario run not found: " + runId));
        if (run.getStatus() != RunStatus.PENDING) {
            throw new IllegalStateException("Run " + runId + " is not in PENDING state (status=" + run.getStatus() + ")");
        }
        if (run.getOperationType() != OperationType.SCENARIO) {
            throw new IllegalArgumentException("Run " + runId + " is not a SCENARIO run");
        }
        asyncExecutor.submit(() -> execute(run.getBenchmarkId(), runId));
    }

    public BenchmarkRun startRun(String benchmarkId, StartScenarioRunRequest request) {
        PreparedScenarioRunResponse prepared = prepareRun(benchmarkId, request);
        confirmRun(prepared.runId());
        return runRepository.findById(prepared.runId()).orElseThrow();
    }

    private void execute(String benchmarkId, String runId) {
        try {
            BenchmarkRun run = runRepository.findById(runId).orElseThrow();
            Benchmark benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
            LogicalSchema schema = loadSchema(benchmark);
            StartScenarioRunRequest request = parseRequest(run.getConfigJson());
            int iterations = run.getIterations() == null ? 1 : run.getIterations();
            ScenarioParams params = request.params();

            run.setStatus(RunStatus.RUNNING);
            runRepository.save(run);
            broadcastRunStatus(benchmarkId, run);

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (BenchmarkResult result : run.getResults()) {
                futures.add(CompletableFuture.runAsync(
                        () -> runForDatabase(benchmarkId, runId, result.getId(), params, iterations, schema),
                        asyncExecutor));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

            finalizeRun(benchmarkId, runId);
        } catch (Exception e) {
            log.error("Scenario run {} failed", runId, e);
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
                                 ScenarioParams params,
                                 int iterations,
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
        if (!ScenarioApplicability.isApplicable(params.type(), engine)) {
            markSkipped(benchmarkId, runId, resultId,
                    "Scenario " + params.type() + " not applicable for " + db.getDbName());
            return;
        }
        if (!driverFactory.supports(engine) || db.getStatus() != DatabaseStatus.RUNNING || db.getHostPort() == null) {
            markSkipped(benchmarkId, runId, resultId, "Engine not supported or container not running");
            return;
        }
        EngineDriver driver = driverFactory.driverFor(engine).orElseThrow();
        EmbeddingMap embeddings = EmbeddingMap.from(embeddingLoader.parse(db.getEmbeddingMappings()));

        ScenarioContext ctx = new ScenarioContext(
                benchmarkId,
                db.getId(),
                db.getDbName(),
                db.getDbVersion(),
                HOST_ADDRESS,
                db.getHostPort(),
                schema,
                embeddings,
                params);

        markStarted(benchmarkId, runId, resultId);
        ContainerStatsCollector.Handle statsHandle = statsCollector.start(
                benchmarkId, runId, resultId, db.getId(), db.getDbName(), db.getContainerId(),
                "scenario:" + params.type().name().toLowerCase());

        try {
            List<TimedOperation> perIteration = new ArrayList<>(iterations);
            ScenarioResult lastResult = null;
            for (int i = 0; i < iterations; i++) {
                EngineDriver.ScenarioOutcome outcome = driver.runScenario(ctx);
                perIteration.add(outcome.timed());
                lastResult = outcome.result();
            }
            TimedOperation merged = mergeTimedOps(perIteration);
            persistSuccess(benchmarkId, runId, resultId, merged, lastResult);
        } catch (Exception ex) {
            log.error("Scenario {} failed for db {} run {}: {}", params.type(), db.getDbName(), runId, ex.getMessage(), ex);
            markFailed(benchmarkId, runId, resultId, ex.getMessage());
        } finally {
            ResourceMetricsSummary summary = statsCollector.stop(statsHandle);
            persistResourceSummary(benchmarkId, runId, resultId, summary);
        }
    }

    private TimedOperation mergeTimedOps(List<TimedOperation> ops) {
        if (ops.size() == 1) return ops.get(0);
        long dbTimeNs = 0;
        long wireTimeNs = 0;
        long rowsAffected = 0;
        int totalSamples = 0;
        for (TimedOperation op : ops) {
            dbTimeNs += op.dbTimeNs();
            wireTimeNs += op.wireTimeNs();
            rowsAffected += op.rowsAffected();
            totalSamples += op.sampleDbTimeNs().length;
        }
        long[] merged = new long[totalSamples];
        int offset = 0;
        for (TimedOperation op : ops) {
            long[] samples = op.sampleDbTimeNs();
            System.arraycopy(samples, 0, merged, offset, samples.length);
            offset += samples.length;
        }
        return TimedOperation.builder()
                .dbTimeNs(dbTimeNs)
                .wireTimeNs(wireTimeNs)
                .rowsAffected(rowsAffected)
                .sampleDbTimeNs(merged)
                .build();
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

    private void persistSuccess(String benchmarkId, String runId, String resultId,
                                  TimedOperation timed, ScenarioResult result) {
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
            if (result != null) {
                r.setScenarioResultJson(result.resultJson());
                r.setScenarioResultHash(result.canonicalHash());
                r.setScenarioRowsReturned(result.rowsReturned());
            }
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

    private void persistResourceSummary(String benchmarkId, String runId, String resultId,
                                          ResourceMetricsSummary summary) {
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

    private void finalizeRun(String benchmarkId, String runId) {
        transactionTemplate.executeWithoutResult(s -> {
            BenchmarkRun run = runRepository.findById(runId).orElseThrow();
            run.setFinishedAt(Instant.now());
            run.setStatus(aggregateStatus(run.getResults()));
            run.setScenarioConsistencyStatus(computeConsistency(run.getResults()));
            runRepository.save(run);
            broadcastRunStatus(benchmarkId, run);
        });
    }

    private String computeConsistency(List<BenchmarkResult> results) {
        Set<String> successfulHashes = new HashSet<>();
        boolean anyIncomplete = false;
        for (BenchmarkResult r : results) {
            if (r.getStatus() == RunStatus.SUCCESS && r.getScenarioResultHash() != null) {
                successfulHashes.add(r.getScenarioResultHash());
            } else if (r.getStatus() != RunStatus.SKIPPED) {
                anyIncomplete = true;
            }
        }
        if (successfulHashes.isEmpty()) return CONSISTENCY_INCOMPLETE;
        if (successfulHashes.size() > 1) return CONSISTENCY_MISMATCH;
        return anyIncomplete ? CONSISTENCY_INCOMPLETE : CONSISTENCY_MATCH;
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

    private boolean isEngineApplicable(ScenarioType type, BenchmarkDatabase db) {
        try {
            DatabaseEngine engine = DatabaseEngine.of(db.getDbName());
            return ScenarioApplicability.isApplicable(type, engine);
        } catch (IllegalArgumentException e) {
            return false;
        }
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

    private void validateRequest(StartScenarioRunRequest request) {
        if (request == null || request.params() == null) {
            throw new IllegalArgumentException("scenarioParams is required");
        }
        if (request.databaseIds() == null || request.databaseIds().isEmpty()) {
            throw new IllegalArgumentException("At least one database must be selected");
        }
        if (request.params() instanceof TraversalParams tp && (tp.startLogicalId() == null || tp.startLogicalId().isBlank())) {
            throw new IllegalArgumentException("startLogicalId is required for GRAPH_TRAVERSAL");
        }
        if (request.params() instanceof KnnParams kp && (kp.queryVector() == null || kp.queryVector().length == 0)) {
            throw new IllegalArgumentException("queryVector is required for VECTOR_KNN");
        }
    }

    private void broadcastRunStatus(String benchmarkId, BenchmarkRun run) {
        sse.sendEvent(benchmarkId, SseEvents.EVENT_SCENARIO_RUN_STATUS,
                Map.of(
                        "runId", run.getId(),
                        "status", run.getStatus().name(),
                        "consistencyStatus",
                        run.getScenarioConsistencyStatus() == null ? "" : run.getScenarioConsistencyStatus()
                ));
    }

    private void broadcastResult(String benchmarkId, String runId, BenchmarkResult result) {
        sse.sendEvent(benchmarkId, SseEvents.EVENT_SCENARIO_RESULT_STATUS,
                Map.of("runId", runId, "result", ScenarioResultResponse.from(result)));
    }

    private String serializeQuietly(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return null;
        }
    }

    private StartScenarioRunRequest parseRequest(String configJson) {
        try {
            return objectMapper.readValue(configJson, StartScenarioRunRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse scenario configJson", e);
        }
    }
}
