package com.dbagnets.backend.benchmark.run.application;

import com.dbagnets.backend.benchmark.result.application.DataSizeProbe;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkResult;
import com.dbagnets.backend.benchmark.run.persistence.RunStatus;
import com.dbagnets.backend.engine.driver.api.EngineDriver;
import com.dbagnets.backend.engine.driver.api.InsertMode;
import com.dbagnets.backend.engine.driver.api.ReadContext;
import com.dbagnets.backend.engine.registry.EntityIdRegistry.RegistryEntry;
import com.dbagnets.backend.engine.resource.ContainerStatsCollector;
import com.dbagnets.backend.engine.resource.ResourceMetricsSummary;
import com.dbagnets.backend.engine.schema.EmbeddingMap;
import com.dbagnets.backend.engine.schema.LogicalSchema;
import com.dbagnets.backend.shared.entity.BenchmarkDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public abstract class AbstractEntityRunOrchestrator extends AbstractRunOrchestrator {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Autowired protected DataSizeProbe dataSizeProbe;

    public record EntityDatabaseContext(BenchmarkDatabase db, EngineDriver driver,
                                         EmbeddingMap embeddings, List<RegistryEntry> targets) {}

    protected Optional<EntityDatabaseContext> resolveEntityContext(String benchmarkId, String runId,
                                                                    String resultId, String entityName,
                                                                    List<String> selectedLogicalIds) {
        Optional<BenchmarkRunSupport.DatabaseContext> ctxOpt =
                runSupport.resolveDatabaseContext(benchmarkId, resultId, resultBroadcast(benchmarkId, runId));
        if (ctxOpt.isEmpty()) return Optional.empty();
        Optional<List<RegistryEntry>> targetsOpt = runSupport.resolveTargets(
                ctxOpt.get().db().getId(), entityName, selectedLogicalIds, resultId,
                resultBroadcast(benchmarkId, runId));
        return targetsOpt.map(registryEntries -> new EntityDatabaseContext(
                ctxOpt.get().db(), ctxOpt.get().driver(), ctxOpt.get().embeddings(), registryEntries));
    }

    protected void warmup(EngineDriver driver,
                          LogicalSchema schema,
                          EmbeddingMap embeddings,
                          String benchmarkId,
                          BenchmarkDatabase db,
                          String entityName,
                          List<RegistryEntry> targets) {
        if (targets.isEmpty()) return;
        try {
            ReadContext warmCtx = new ReadContext(
                    benchmarkId, db.getId(), db.getDbName(), db.getDbVersion(),
                    hostAddress, db.getHostPort(), schema, embeddings, entityName,
                    targets.subList(0, 1), false, InsertMode.SINGLE);
            driver.read(warmCtx);
        } catch (Exception ignored) {
            // warmup is best-effort — first measurement may carry cold-cache overhead, that's OK
        }
    }

    protected Long safeProbe(BenchmarkDatabase db) {
        try {
            return dataSizeProbe.sizeOf(db, hostAddress);
        } catch (Exception ex) {
            log.debug("Size probe failed for {}: {}", db.getDbName(), ex.getMessage());
            return null;
        }
    }

    protected void markStartedWithSize(String benchmarkId, String runId, String resultId, Long sizeBefore) {
        transactionTemplate.executeWithoutResult(s -> {
            BenchmarkResult r = resultRepository.findById(resultId).orElseThrow();
            r.setStatus(RunStatus.RUNNING);
            r.setStartedAt(Instant.now());
            r.setDataSizeBefore(sizeBefore);
            resultRepository.save(r);
            broadcastResult(benchmarkId, runId, r);
        });
    }

    protected void runEntityOperation(String benchmarkId, String runId, String resultId, String entityName,
                                       List<String> selectedLogicalIds, LogicalSchema schema, String operationLabel,
                                       AbstractRunOrchestrator.ThrowingConsumer<EntityDatabaseContext> body) {
        Optional<EntityDatabaseContext> ctxOpt = resolveEntityContext(benchmarkId, runId, resultId, entityName, selectedLogicalIds);
        if (ctxOpt.isEmpty()) return;
        EntityDatabaseContext ctx = ctxOpt.get();
        BenchmarkDatabase db = ctx.db();
        warmup(ctx.driver(), schema, ctx.embeddings(), benchmarkId, db, entityName, ctx.targets());
        Long sizeBefore = safeProbe(db);
        markStartedWithSize(benchmarkId, runId, resultId, sizeBefore);
        ContainerStatsCollector.Handle statsHandle = statsCollector.start(
                benchmarkId, runId, resultId, db.getId(), db.getDbName(), db.getContainerId(), operationLabel);
        try {
            body.accept(ctx);
        } catch (Exception ex) {
            log.error("{} failed for db {} run {}: {}", operationLabel, db.getDbName(), runId, ex.getMessage(), ex);
            runSupport.markFailed(resultId, ex.getMessage(), resultBroadcast(benchmarkId, runId));
        } finally {
            ResourceMetricsSummary summary = statsCollector.stop(statsHandle);
            runSupport.persistResourceSummary(resultId, summary, resultBroadcast(benchmarkId, runId));
        }
    }

}