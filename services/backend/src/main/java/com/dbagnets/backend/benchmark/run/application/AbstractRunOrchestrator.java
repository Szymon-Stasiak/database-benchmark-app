package com.dbagnets.backend.benchmark.run.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.dbagnets.backend.benchmark.run.persistence.BenchmarkResult;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkResultRepository;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkRun;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkRunRepository;
import com.dbagnets.backend.benchmark.run.persistence.RunStatus;
import com.dbagnets.backend.engine.resource.ContainerStatsCollector;
import com.dbagnets.backend.engine.resource.ResourceMetricsSummary;
import com.dbagnets.backend.engine.timing.LatencyStats;
import com.dbagnets.backend.engine.timing.TimedOperation;
import com.dbagnets.backend.infrastructure.persistence.BenchmarkRepository;
import com.dbagnets.backend.shared.entity.BenchmarkDatabase;
import com.dbagnets.backend.shared.event.BenchmarkEventPort;

public abstract class AbstractRunOrchestrator {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Value("${app.container-host}")
    protected String hostAddress;

    @Autowired protected BenchmarkRepository benchmarkRepository;

    @Autowired protected BenchmarkRunRepository runRepository;

    @Autowired protected BenchmarkResultRepository resultRepository;

    @Autowired protected BenchmarkEventPort sse;

    @Autowired protected ObjectMapper objectMapper;

    @Autowired protected TransactionTemplate transactionTemplate;

    @Autowired protected ContainerStatsCollector statsCollector;

    @Autowired protected BenchmarkRunSupport runSupport;

    protected final ExecutorService asyncExecutor =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());

    protected abstract String runStatusEventName();

    protected abstract String resultStatusEventName();

    protected abstract Object toResultResponse(BenchmarkResult result);

    protected abstract String runLabel();

    protected void broadcastRunStatus(String benchmarkId, BenchmarkRun run) {
        sse.sendEvent(
                benchmarkId,
                runStatusEventName(),
                Map.of("runId", run.getId(), "status", run.getStatus().name()));
    }

    protected void broadcastResult(String benchmarkId, String runId, BenchmarkResult result) {
        sse.sendEvent(
                benchmarkId,
                resultStatusEventName(),
                Map.of("runId", runId, "result", toResultResponse(result)));
    }

    protected Consumer<BenchmarkResult> resultBroadcast(String benchmarkId, String runId) {
        return r -> broadcastResult(benchmarkId, runId, r);
    }

    protected List<String> parseSelectedIds(String json) {
        if (json == null) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Cannot parse selectedIdsJson: {}", e.getMessage());
            return List.of();
        }
    }

    protected void markRunRunning(String benchmarkId, BenchmarkRun run) {
        run.setStatus(RunStatus.RUNNING);
        runRepository.save(run);
        broadcastRunStatus(benchmarkId, run);
    }

    protected void markRunFailed(String benchmarkId, String runId) {
        transactionTemplate.executeWithoutResult(
                s -> {
                    BenchmarkRun run = runRepository.findById(runId).orElseThrow();
                    run.setStatus(RunStatus.FAILED);
                    run.setFinishedAt(Instant.now());
                    runRepository.save(run);
                    broadcastRunStatus(benchmarkId, run);
                });
    }

    protected void wrapExecute(String benchmarkId, String runId, Runnable body) {
        try {
            body.run();
        } catch (Exception e) {
            log.error("{} run {} failed", runLabel(), runId, e);
            markRunFailed(benchmarkId, runId);
        }
    }

    protected void fanOut(BenchmarkRun run, Consumer<String> perResult) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (BenchmarkResult result : run.getResults()) {
            futures.add(
                    CompletableFuture.runAsync(
                            () -> perResult.accept(result.getId()), asyncExecutor));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    }

    protected void finalizeRun(String benchmarkId, String runId) {
        runSupport.finalizeRun(runId, finalized -> broadcastRunStatus(benchmarkId, finalized));
    }

    protected void applyCoreTimings(BenchmarkResult r, TimedOperation timed) {
        r.setStatus(RunStatus.SUCCESS);
        r.setFinishedAt(Instant.now());
        r.setDbTimeNs(timed.dbTimeNs());
        r.setWireTimeNs(timed.wireTimeNs());
        r.setOverheadNs(timed.overheadNs());
        r.setRowsAffected(timed.rowsAffected());
    }

    protected void applyPercentiles(BenchmarkResult r, TimedOperation timed) {
        LatencyStats stats = LatencyStats.from(timed.sampleDbTimeNs());
        r.setP50DbTimeNs(stats.p50Ns());
        r.setP95DbTimeNs(stats.p95Ns());
        r.setP99DbTimeNs(stats.p99Ns());
        r.setMeanDbTimeNs(stats.meanNs());
        r.setSamplesRecorded(stats.sampleCount());
    }

    protected void persistSuccess(
            String benchmarkId,
            String runId,
            String resultId,
            TimedOperation timed,
            Consumer<BenchmarkResult> extra) {
        transactionTemplate.executeWithoutResult(
                s -> {
                    BenchmarkResult r = resultRepository.findById(resultId).orElseThrow();
                    applyCoreTimings(r, timed);
                    applyPercentiles(r, timed);
                    extra.accept(r);
                    resultRepository.save(r);
                    broadcastResult(benchmarkId, runId, r);
                });
    }

    protected void runDatabaseOperation(
            String benchmarkId,
            String runId,
            String resultId,
            String operationLabel,
            ThrowingConsumer<BenchmarkRunSupport.DatabaseContext> body) {
        Optional<BenchmarkRunSupport.DatabaseContext> ctxOpt =
                runSupport.resolveDatabaseContext(
                        benchmarkId, resultId, resultBroadcast(benchmarkId, runId));
        if (ctxOpt.isEmpty()) return;
        BenchmarkRunSupport.DatabaseContext ctx = ctxOpt.get();
        BenchmarkDatabase db = ctx.db();
        runSupport.markStarted(resultId, resultBroadcast(benchmarkId, runId));
        ContainerStatsCollector.Handle statsHandle =
                statsCollector.start(
                        benchmarkId,
                        runId,
                        resultId,
                        db.getId(),
                        db.getDbName(),
                        db.getContainerId(),
                        operationLabel);
        try {
            body.accept(ctx);
        } catch (Exception ex) {
            log.error(
                    "{} failed for db {} run {}: {}",
                    operationLabel,
                    db.getDbName(),
                    runId,
                    ex.getMessage(),
                    ex);
            runSupport.markFailed(resultId, ex.getMessage(), resultBroadcast(benchmarkId, runId));
        } finally {
            ResourceMetricsSummary summary = statsCollector.stop(statsHandle);
            runSupport.persistResourceSummary(
                    resultId, summary, resultBroadcast(benchmarkId, runId));
        }
    }

    @FunctionalInterface
    public interface ThrowingConsumer<T> {
        void accept(T t) throws Exception;
    }
}
