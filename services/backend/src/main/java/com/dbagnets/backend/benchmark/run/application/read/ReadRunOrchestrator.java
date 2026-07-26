package com.dbagnets.backend.benchmark.run.application.read;

import com.dbagnets.backend.benchmark.run.application.AbstractEntityRunOrchestrator;
import com.dbagnets.backend.benchmark.run.application.BenchmarkRunSupport;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkRun;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkResult;
import com.dbagnets.backend.benchmark.run.persistence.OperationType;
import com.dbagnets.backend.benchmark.run.persistence.RunStatus;
import com.dbagnets.backend.engine.driver.InsertMode;
import com.dbagnets.backend.engine.driver.ReadContext;
import com.dbagnets.backend.engine.driver.ReadDepth;
import com.dbagnets.backend.benchmark.run.api.dto.PreparedRunResponse;
import com.dbagnets.backend.benchmark.run.api.dto.ReadResultResponse;
import com.dbagnets.backend.benchmark.run.api.dto.StartReadRunRequest;
import com.dbagnets.backend.engine.schema.LogicalSchema;
import com.dbagnets.backend.engine.timing.TimedOperation;
import com.dbagnets.backend.shared.entity.Benchmark;
import com.dbagnets.backend.shared.entity.BenchmarkDatabase;
import com.dbagnets.backend.infrastructure.sse.SseEvents;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Slf4j
@Service
public class ReadRunOrchestrator extends AbstractEntityRunOrchestrator {

    @Override
    protected String runStatusEventName() { return SseEvents.EVENT_READ_RUN_STATUS; }

    @Override
    protected String resultStatusEventName() { return SseEvents.EVENT_READ_RESULT_STATUS; }

    @Override
    protected Object toResultResponse(BenchmarkResult result) { return ReadResultResponse.from(result); }

    @Override
    protected String runLabel() { return "Read"; }

    @Transactional
    public PreparedRunResponse prepareRun(String benchmarkId, StartReadRunRequest request) {
        validateRequest(request);
        var ctx = runSupport.prepareEntityRunContext(benchmarkId, request);
        BenchmarkRun run = runSupport.createEntityRun(benchmarkId, OperationType.READ,
                request.entityName(), request.databaseIds(), ctx.benchmark(), ctx.selectedIds(), ctx.preview(), request,
                r -> broadcastRunStatus(benchmarkId, r));
        sse.sendEvent(benchmarkId, SseEvents.EVENT_READ_RUN_PREPARED,
                Map.of("runId", run.getId(), "preview", ctx.preview()));
        return new PreparedRunResponse(run.getId(), benchmarkId, OperationType.READ.name(),
                request.entityName(), run.getStatus().name(), ctx.preview());
    }

    public void confirmRun(String runId) {
        BenchmarkRun run = runRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Read run not found: " + runId));
        if (run.getStatus() != RunStatus.PENDING) {
            throw new IllegalStateException("Run " + runId + " is not in PENDING state (status=" + run.getStatus() + ")");
        }
        if (run.getOperationType() != OperationType.READ) {
            throw new IllegalArgumentException("Run " + runId + " is not a READ run");
        }
        if (run.getSelectedIdsJson() == null) {
            throw new IllegalStateException("Run " + runId + " has no selected IDs — prepare first");
        }
        asyncExecutor.submit(() -> execute(run.getBenchmarkId(), runId));
    }

    public BenchmarkRun startRun(String benchmarkId, StartReadRunRequest request) {
        PreparedRunResponse prepared = prepareRun(benchmarkId, request);
        confirmRun(prepared.runId());
        return runRepository.findById(prepared.runId()).orElseThrow();
    }

    private void execute(String benchmarkId, String runId) {
        wrapExecute(benchmarkId, runId, () -> {
            BenchmarkRun run = runRepository.findById(runId).orElseThrow();
            Benchmark benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
            LogicalSchema schema = runSupport.loadSchema(benchmark);
            List<String> selectedIds = parseSelectedIds(run.getSelectedIdsJson());
            ReadDepth readDepth = parseReadDepth(run.getConfigJson());
            InsertMode mode = parseMode(run.getConfigJson());
            int iterations = parseIterations(run.getConfigJson());
            String entityName = run.getEntityName();

            markRunRunning(benchmarkId, run);
            fanOut(run, resultId -> runForDatabase(benchmarkId, runId, resultId,
                    entityName, selectedIds, readDepth, mode, iterations, schema));
            finalizeRun(benchmarkId, runId);
        });
    }

    private void runForDatabase(String benchmarkId, String runId, String resultId, String entityName,
                                 List<String> selectedLogicalIds, ReadDepth readDepth, InsertMode mode,
                                 int iterations, LogicalSchema schema) {
        runEntityOperation(benchmarkId, runId, resultId, entityName, selectedLogicalIds, schema, "read", ctx -> {
            BenchmarkDatabase db = ctx.db();
            ReadContext readCtx = new ReadContext(benchmarkId, db.getId(), db.getDbName(), db.getDbVersion(),
                    hostAddress, db.getHostPort(), schema, ctx.embeddings(), entityName, ctx.targets(), readDepth, mode);
            List<TimedOperation> perIteration = new ArrayList<>(iterations);
            for (int i = 0; i < iterations; i++) {
                perIteration.add(ctx.driver().read(readCtx));
            }
            TimedOperation timed = BenchmarkRunSupport.mergeTimedOps(perIteration);
            Long sizeAfter = safeProbe(db);
            persistSuccess(benchmarkId, runId, resultId, timed, sizeAfter);
        });
    }

    private void persistSuccess(String benchmarkId, String runId, String resultId, TimedOperation timed, Long sizeAfter) {
        persistSuccess(benchmarkId, runId, resultId, timed, r -> r.setDataSizeAfter(sizeAfter));
    }

    private void validateRequest(StartReadRunRequest request) {
        if (request.entityName() == null || request.entityName().isBlank()) {
            throw new IllegalArgumentException("entityName is required");
        }
        if (request.databaseIds() == null || request.databaseIds().isEmpty()) {
            throw new IllegalArgumentException("At least one database must be selected");
        }
    }

    private ReadDepth parseReadDepth(String configJson) {
        if (configJson == null) return ReadDepth.NONE;
        try {
            return objectMapper.readValue(configJson, StartReadRunRequest.class).readDepthOrDefault();
        } catch (Exception e) { return ReadDepth.NONE; }
    }

    private InsertMode parseMode(String configJson) {
        if (configJson == null) return InsertMode.SINGLE;
        try {
            return objectMapper.readValue(configJson, StartReadRunRequest.class).modeOrDefault();
        } catch (Exception e) { return InsertMode.SINGLE; }
    }

    private int parseIterations(String configJson) {
        if (configJson == null) return 1;
        try {
            return objectMapper.readValue(configJson, StartReadRunRequest.class).iterationsOrDefault();
        } catch (Exception e) { return 1; }
    }
}