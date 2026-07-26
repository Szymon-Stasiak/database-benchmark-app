package com.dbagnets.backend.benchmark.run.application.scenario;

import com.dbagnets.backend.benchmark.run.application.AbstractRunOrchestrator;
import com.dbagnets.backend.benchmark.run.application.BenchmarkRunSupport;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkRun;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkResult;
import com.dbagnets.backend.benchmark.run.persistence.OperationType;
import com.dbagnets.backend.benchmark.run.persistence.RunStatus;
import com.dbagnets.backend.engine.driver.EngineDriver;
import com.dbagnets.backend.benchmark.run.api.dto.PreparedScenarioRunResponse;
import com.dbagnets.backend.benchmark.run.api.dto.ScenarioResultResponse;
import com.dbagnets.backend.benchmark.run.api.dto.StartScenarioRunRequest;
import com.dbagnets.backend.engine.scenario.KnnParams;
import com.dbagnets.backend.engine.scenario.ScenarioApplicability;
import com.dbagnets.backend.engine.scenario.ScenarioContext;
import com.dbagnets.backend.engine.scenario.ScenarioParams;
import com.dbagnets.backend.engine.scenario.ScenarioResult;
import com.dbagnets.backend.engine.scenario.ScenarioType;
import com.dbagnets.backend.engine.scenario.TraversalParams;
import com.dbagnets.backend.engine.schema.LogicalSchema;
import com.dbagnets.backend.engine.timing.TimedOperation;
import com.dbagnets.backend.shared.entity.Benchmark;
import com.dbagnets.backend.shared.entity.BenchmarkDatabase;
import com.dbagnets.backend.domain.DatabaseEngine;
import com.dbagnets.backend.infrastructure.sse.SseEvents;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

@Slf4j
@Service
public class ScenarioRunOrchestrator extends AbstractRunOrchestrator {

    public static final String CONSISTENCY_MATCH = "MATCH";
    public static final String CONSISTENCY_MISMATCH = "MISMATCH";
    public static final String CONSISTENCY_INCOMPLETE = "INCOMPLETE";

    @Override
    protected String runStatusEventName() { return SseEvents.EVENT_SCENARIO_RUN_STATUS; }

    @Override
    protected String resultStatusEventName() { return SseEvents.EVENT_SCENARIO_RESULT_STATUS; }

    @Override
    protected Object toResultResponse(BenchmarkResult result) { return ScenarioResultResponse.from(result); }

    @Override
    protected String runLabel() { return "Scenario"; }

    @Override
    protected void broadcastRunStatus(String benchmarkId, BenchmarkRun run) {
        sse.sendEvent(benchmarkId, runStatusEventName(), Map.of(
                "runId", run.getId(),
                "status", run.getStatus().name(),
                "consistencyStatus", run.getScenarioConsistencyStatus() == null ? "" : run.getScenarioConsistencyStatus()));
    }

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
        run.setConfigJson(runSupport.serializeQuietly(request));

        List<PreparedScenarioRunResponse.Applicability> applicability = new ArrayList<>();
        for (String databaseId : request.databaseIds()) {
            BenchmarkDatabase db = BenchmarkRunSupport.findDatabase(benchmark, databaseId);
            BenchmarkResult result = new BenchmarkResult(db.getId(), db.getDbName());
            result.setScenarioType(type.name());
            run.addResult(result);
            boolean applicable = isEngineApplicable(type, db);
            applicability.add(new PreparedScenarioRunResponse.Applicability(db.getId(), db.getDbName(),
                    applicable, applicable ? null : "Scenario " + type + " not applicable for " + db.getDbName()));
        }
        runRepository.save(run);
        broadcastRunStatus(benchmarkId, run);
        sse.sendEvent(benchmarkId, SseEvents.EVENT_SCENARIO_RUN_PREPARED,
                Map.of("runId", run.getId(), "applicability", applicability));

        return new PreparedScenarioRunResponse(run.getId(), benchmarkId, type.name(), run.getStatus().name(), applicability);
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
        wrapExecute(benchmarkId, runId, () -> {
            BenchmarkRun run = runRepository.findById(runId).orElseThrow();
            Benchmark benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
            LogicalSchema schema = runSupport.loadSchema(benchmark);
            StartScenarioRunRequest request = parseRequest(run.getConfigJson());
            int iterations = run.getIterations() == null ? 1 : run.getIterations();
            ScenarioParams params = request.params();

            markRunRunning(benchmarkId, run);
            fanOut(run, resultId -> runForDatabase(benchmarkId, runId, resultId, params, iterations, schema));
            finalizeScenarioRun(benchmarkId, runId);
        });
    }

    private void runForDatabase(String benchmarkId, String runId, String resultId, ScenarioParams params,
                                 int iterations, LogicalSchema schema) {
        String label = "scenario:" + params.type().name().toLowerCase();
        runDatabaseOperation(benchmarkId, runId, resultId, label, ctx -> {
            BenchmarkDatabase db = ctx.db();
            ScenarioContext scenarioCtx = new ScenarioContext(benchmarkId, db.getId(), db.getDbName(), db.getDbVersion(),
                    hostAddress, db.getHostPort(), schema, ctx.embeddings(), params);
            List<TimedOperation> perIteration = new ArrayList<>(iterations);
            ScenarioResult lastResult = null;
            for (int i = 0; i < iterations; i++) {
                EngineDriver.ScenarioOutcome outcome = ctx.driver().runScenario(scenarioCtx);
                perIteration.add(outcome.timed());
                lastResult = outcome.result();
            }
            TimedOperation merged = BenchmarkRunSupport.mergeTimedOps(perIteration);
            persistSuccess(benchmarkId, runId, resultId, merged, lastResult);
        });
    }

    private void persistSuccess(String benchmarkId, String runId, String resultId, TimedOperation timed, ScenarioResult result) {
        persistSuccess(benchmarkId, runId, resultId, timed, r -> {
            if (result != null) {
                r.setScenarioResultJson(result.resultJson());
                r.setScenarioResultHash(result.canonicalHash());
                r.setScenarioRowsReturned(result.rowsReturned());
            }
        });
    }

    private void finalizeScenarioRun(String benchmarkId, String runId) {
        transactionTemplate.executeWithoutResult(s -> {
            BenchmarkRun run = runRepository.findById(runId).orElseThrow();
            run.setFinishedAt(Instant.now());
            run.setStatus(BenchmarkRunSupport.aggregateStatus(run.getResults()));
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

    private boolean isEngineApplicable(ScenarioType type, BenchmarkDatabase db) {
        try {
            DatabaseEngine engine = DatabaseEngine.of(db.getDbName());
            return ScenarioApplicability.isApplicable(type, engine);
        } catch (IllegalArgumentException e) {
            return false;
        }
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

    private StartScenarioRunRequest parseRequest(String configJson) {
        try {
            return objectMapper.readValue(configJson, StartScenarioRunRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse scenario configJson", e);
        }
    }
}