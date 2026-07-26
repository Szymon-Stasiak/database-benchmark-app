package com.dbagnets.backend.benchmark.run.application.insert;

import com.dbagnets.backend.benchmark.run.application.AbstractRunOrchestrator;
import com.dbagnets.backend.benchmark.run.application.BenchmarkRunSupport;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkRun;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkResult;
import com.dbagnets.backend.benchmark.run.persistence.OperationType;
import com.dbagnets.backend.benchmark.run.persistence.RunStatus;
import com.dbagnets.backend.engine.cascade.CascadePlan;
import com.dbagnets.backend.engine.cascade.CascadePlanner;
import com.dbagnets.backend.engine.cascade.LeafChoice;
import com.dbagnets.backend.engine.datagen.GeneratedRow;
import com.dbagnets.backend.engine.datagen.PrimaryKeyVault;
import com.dbagnets.backend.engine.datagen.RecordBuilder;
import com.dbagnets.backend.engine.driver.BatchProgress;
import com.dbagnets.backend.engine.driver.InsertContext;
import com.dbagnets.backend.engine.driver.InsertMode;
import com.dbagnets.backend.benchmark.run.api.dto.BatchProgressEvent;
import com.dbagnets.backend.benchmark.run.api.dto.EdgeRatioDto;
import com.dbagnets.backend.benchmark.run.api.dto.EntityCascadeChoiceDto;
import com.dbagnets.backend.benchmark.run.api.dto.InsertResultResponse;
import com.dbagnets.backend.benchmark.run.api.dto.StartInsertRunRequest;
import com.dbagnets.backend.benchmark.result.application.DataSizeProbe;
import com.dbagnets.backend.engine.registry.EntityIdRegistry;
import com.dbagnets.backend.engine.schema.LogicalSchema;
import com.dbagnets.backend.engine.timing.RecordedId;
import com.dbagnets.backend.engine.timing.TimedOperation;
import com.dbagnets.backend.shared.entity.Benchmark;
import com.dbagnets.backend.shared.entity.BenchmarkDatabase;
import com.dbagnets.backend.infrastructure.sse.SseEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Slf4j
@Service
@RequiredArgsConstructor
public class InsertRunOrchestrator extends AbstractRunOrchestrator {

    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int DEFAULT_WORKER_COUNT = 4;

    private final EntityIdRegistry registry;
    private final CascadePlanner planner;
    private final RecordBuilder recordBuilder;
    private final DataSizeProbe dataSizeProbe;
    private final InsertProgressTracker progressTracker;

    @Override
    protected String runStatusEventName() { return SseEvents.EVENT_INSERT_RUN_STATUS; }

    @Override
    protected String resultStatusEventName() { return SseEvents.EVENT_INSERT_RESULT_STATUS; }

    @Override
    protected Object toResultResponse(BenchmarkResult result) { return InsertResultResponse.from(result); }

    @Override
    protected String runLabel() { return "Insert"; }

    @Transactional
    public BenchmarkRun startRun(String benchmarkId, StartInsertRunRequest request) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId)
                .orElseThrow(() -> new NoSuchElementException("Benchmark not found: " + benchmarkId));
        validateRequest(request);

        LogicalSchema schema = runSupport.loadSchema(benchmark);
        CascadePlan plan = planFor(schema, request);

        BenchmarkRun run = new BenchmarkRun(benchmarkId, OperationType.INSERT);
        run.setMode(request.mode().name());
        run.setBatchSize(BenchmarkRunSupport.orDefault(request.batchSize(), DEFAULT_BATCH_SIZE));
        run.setWorkerCount(BenchmarkRunSupport.orDefault(request.workerCount(), DEFAULT_WORKER_COUNT));
        run.setEntityName(primaryEntityName(request));
        run.setRecordCount(totalLeafRecordCount(request));
        run.setConfigJson(runSupport.serializeQuietly(request));
        run.setCascadeJson(runSupport.serializeQuietly(plan));

        for (String databaseId : request.databaseIds()) {
            BenchmarkDatabase db = BenchmarkRunSupport.findDatabase(benchmark, databaseId);
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
        wrapExecute(benchmarkId, runId, () -> {
            BenchmarkRun run = runRepository.findById(runId).orElseThrow();
            Benchmark benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
            LogicalSchema schema = runSupport.loadSchema(benchmark);
            CascadePlan plan = planFor(schema, request);
            markRunRunning(benchmarkId, run);

            PrimaryKeyVault vault = new PrimaryKeyVault();
            Map<String, List<GeneratedRow>> rowsByEntity = recordBuilder.generateAll(schema, plan, vault);

            fanOut(run, resultId -> runForDatabase(benchmarkId, runId, resultId, request, schema, plan, rowsByEntity));
            finalizeRun(benchmarkId, runId);
        });
    }

    private void runForDatabase(String benchmarkId, String runId, String resultId, StartInsertRunRequest request,
                                 LogicalSchema schema, CascadePlan plan, Map<String, List<GeneratedRow>> rowsByEntity) {
        runDatabaseOperation(benchmarkId, runId, resultId, "insert", ctx -> {
            BenchmarkDatabase db = ctx.db();
            BatchProgress progress = new BatchProgress() {
                @Override
                public void onBatch(String entityName, int idx, int total, long done, long all) {
                    BatchProgressEvent event = new BatchProgressEvent(runId, resultId, db.getId(), entityName, idx, total, done, all);
                    progressTracker.record(event);
                    sse.sendEvent(benchmarkId, SseEvents.EVENT_INSERT_BATCH_PROGRESS, event);
                }

                @Override
                public void onEntityFinished(String entityName) {
                    dataSizeProbe.invalidate(db.getId());
                    sse.sendEvent(benchmarkId, SseEvents.EVENT_DATABASE_SIZE_DIRTY,
                            Map.of("databaseId", db.getId(), "entityName", entityName));
                }
            };
            InsertContext insertCtx = new InsertContext(benchmarkId, db.getId(), db.getDbName(), db.getDbVersion(),
                    hostAddress, db.getHostPort(), schema, ctx.embeddings(), plan, rowsByEntity,
                    request.mode() == null ? InsertMode.BATCH : request.mode(),
                    BenchmarkRunSupport.orDefault(request.batchSize(), DEFAULT_BATCH_SIZE), progress);
            TimedOperation timed = ctx.driver().insert(insertCtx);
            persistSuccess(benchmarkId, runId, resultId, timed);
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

    private CascadePlan planFor(LogicalSchema schema, StartInsertRunRequest request) {
        List<LeafChoice> leaves = request.entities().stream()
                .map(c -> new LeafChoice(c.entityName(), c.recordCount())).toList();
        Map<String, Double> overrides = new HashMap<>();
        for (EntityCascadeChoiceDto entity : request.entities()) {
            for (EdgeRatioDto edge : entity.edgeRatios()) {
                overrides.put(edge.parentEntity() + "_" + edge.childEntity(), edge.ratio());
            }
        }
        return planner.plan(schema, leaves, overrides);
    }

    private String primaryEntityName(StartInsertRunRequest request) {
        return request.entities().isEmpty() ? null : request.entities().getFirst().entityName();
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
}