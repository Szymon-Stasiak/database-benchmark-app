package com.dbagnets.backend.benchmark.run.application.delete;

import com.dbagnets.backend.benchmark.run.application.AbstractEntityRunOrchestrator;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkRun;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkResult;
import com.dbagnets.backend.benchmark.run.persistence.OperationType;
import com.dbagnets.backend.benchmark.run.persistence.RunStatus;
import com.dbagnets.backend.engine.driver.api.DeleteContext;
import com.dbagnets.backend.engine.driver.api.DeletionMode;
import com.dbagnets.backend.engine.driver.api.InsertMode;
import com.dbagnets.backend.benchmark.run.api.dto.DeleteResultResponse;
import com.dbagnets.backend.benchmark.run.api.dto.PreparedRunResponse;
import com.dbagnets.backend.benchmark.run.api.dto.StartDeleteRunRequest;
import com.dbagnets.backend.engine.registry.EntityIdRegistry;
import com.dbagnets.backend.engine.registry.EntityIdRegistry.RegistryEntry;
import com.dbagnets.backend.engine.schema.LogicalSchema;
import com.dbagnets.backend.engine.timing.TimedOperation;
import com.dbagnets.backend.shared.entity.Benchmark;
import com.dbagnets.backend.shared.entity.BenchmarkDatabase;
import com.dbagnets.backend.infrastructure.sse.SseEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeleteRunOrchestrator extends AbstractEntityRunOrchestrator {

    private final EntityIdRegistry registry;

    @Override
    protected String runStatusEventName() { return SseEvents.EVENT_DELETE_RUN_STATUS; }

    @Override
    protected String resultStatusEventName() { return SseEvents.EVENT_DELETE_RESULT_STATUS; }

    @Override
    protected Object toResultResponse(BenchmarkResult result) { return DeleteResultResponse.from(result); }

    @Override
    protected String runLabel() { return "Delete"; }

    @Transactional
    public PreparedRunResponse prepareRun(String benchmarkId, StartDeleteRunRequest request) {
        validateRequest(request);
        var ctx = runSupport.prepareEntityRunContext(benchmarkId, request);
        BenchmarkRun run = runSupport.createEntityRun(benchmarkId, OperationType.DELETE,
                request.entityName(), request.databaseIds(), ctx.benchmark(), ctx.selectedIds(), ctx.preview(), request,
                r -> broadcastRunStatus(benchmarkId, r));
        sse.sendEvent(benchmarkId, SseEvents.EVENT_DELETE_RUN_PREPARED,
                Map.of("runId", run.getId(), "preview", ctx.preview()));
        return new PreparedRunResponse(run.getId(), benchmarkId, OperationType.DELETE.name(),
                request.entityName(), run.getStatus().name(), ctx.preview());
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
        wrapExecute(benchmarkId, runId, () -> {
            BenchmarkRun run = runRepository.findById(runId).orElseThrow();
            Benchmark benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
            LogicalSchema schema = runSupport.loadSchema(benchmark);
            List<String> selectedIds = parseSelectedIds(run.getSelectedIdsJson());
            DeletionMode deletionMode = parseDeletionMode(run.getConfigJson());
            InsertMode mode = parseMode(run.getConfigJson());
            String entityName = run.getEntityName();

            markRunRunning(benchmarkId, run);
            fanOut(run, resultId -> runForDatabase(benchmarkId, runId, resultId,
                    entityName, selectedIds, deletionMode, mode, schema));
            finalizeRun(benchmarkId, runId);
        });
    }

    private void runForDatabase(String benchmarkId, String runId, String resultId, String entityName,
                                 List<String> selectedLogicalIds, DeletionMode deletionMode,
                                 InsertMode mode, LogicalSchema schema) {
        runEntityOperation(benchmarkId, runId, resultId, entityName, selectedLogicalIds, schema, "delete", ctx -> {
            BenchmarkDatabase db = ctx.db();
            DeleteContext deleteCtx = new DeleteContext(benchmarkId, db.getId(), db.getDbName(), db.getDbVersion(),
                    hostAddress, db.getHostPort(), schema, ctx.embeddings(), entityName, ctx.targets(), deletionMode, mode);
            TimedOperation timed = ctx.driver().delete(deleteCtx);
            List<String> deletedLogicalIds = ctx.targets().stream().map(RegistryEntry::logicalId).toList();
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
        });
    }

    private void persistSuccess(String benchmarkId, String runId, String resultId, TimedOperation timed, Long sizeAfter) {
        persistSuccess(benchmarkId, runId, resultId, timed, r -> {
            var breakdown = new LinkedHashMap<String, Integer>();
            long cascadeTotal = 0L;
            for (var e : timed.cascadeDeletedByEntity().entrySet()) {
                int n = e.getValue().size();
                breakdown.put(e.getKey(), n);
                cascadeTotal += n;
            }
            r.setCascadeRowsAffected(cascadeTotal);
            r.setCascadeBreakdownJson(breakdown.isEmpty() ? null : runSupport.serializeQuietly(breakdown));
            r.setDataSizeAfter(sizeAfter);
        });
    }

    private void validateRequest(StartDeleteRunRequest request) {
        if (request.entityName() == null || request.entityName().isBlank()) {
            throw new IllegalArgumentException("entityName is required");
        }
        if (request.databaseIds() == null || request.databaseIds().isEmpty()) {
            throw new IllegalArgumentException("At least one database must be selected");
        }
    }

    private DeletionMode parseDeletionMode(String configJson) {
        if (configJson == null) return DeletionMode.NATIVE;
        try {
            return objectMapper.readValue(configJson, StartDeleteRunRequest.class).deletionModeOrDefault();
        } catch (Exception e) { return DeletionMode.NATIVE; }
    }

    private InsertMode parseMode(String configJson) {
        if (configJson == null) return InsertMode.SINGLE;
        try {
            return objectMapper.readValue(configJson, StartDeleteRunRequest.class).modeOrDefault();
        } catch (Exception e) { return InsertMode.SINGLE; }
    }
}