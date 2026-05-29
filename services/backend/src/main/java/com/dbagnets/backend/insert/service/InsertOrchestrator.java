package com.dbagnets.backend.insert.service;

import com.dbagnets.backend.entity.Benchmark;
import com.dbagnets.backend.entity.BenchmarkDatabase;
import com.dbagnets.backend.entity.DatabaseStatus;
import com.dbagnets.backend.docker.DockerService;
import com.dbagnets.backend.insert.cascade.CascadePlan;
import com.dbagnets.backend.insert.cascade.CascadeResolver;
import com.dbagnets.backend.insert.cascade.EdgeRatioOverride;
import com.dbagnets.backend.insert.cascade.EntityNode;
import com.dbagnets.backend.insert.cascade.PrimaryKeyRegistry;
import com.dbagnets.backend.insert.datagen.CascadingRecordGenerator;
import com.dbagnets.backend.insert.datagen.GeneratedRecord;
import com.dbagnets.backend.insert.entity.InsertMode;
import com.dbagnets.backend.insert.entity.InsertResult;
import com.dbagnets.backend.insert.entity.InsertRun;
import com.dbagnets.backend.insert.entity.InsertStatus;
import com.dbagnets.backend.insert.model.EdgeRatio;
import com.dbagnets.backend.insert.model.EntityCascadeChoice;
import com.dbagnets.backend.insert.model.InsertResultResponse;
import com.dbagnets.backend.insert.model.InsertRunResponse;
import com.dbagnets.backend.insert.model.StartInsertRunRequest;
import com.dbagnets.backend.insert.repository.InsertResultRepository;
import com.dbagnets.backend.insert.repository.InsertRunRepository;
import com.dbagnets.backend.insert.schema.LogicalSchema;
import com.dbagnets.backend.insert.schema.LogicalSchemaLoader;
import com.dbagnets.backend.insert.model.BatchProgressEvent;
import com.dbagnets.backend.insert.strategy.BatchProgressCallback;
import com.dbagnets.backend.insert.strategy.DatabaseInsertStrategy;
import com.dbagnets.backend.insert.strategy.InsertContext;
import com.dbagnets.backend.insert.strategy.InsertOutcome;
import com.dbagnets.backend.insert.strategy.InsertStrategyFactory;
import com.dbagnets.backend.repository.BenchmarkDatabaseRepository;
import com.dbagnets.backend.repository.BenchmarkRepository;
import com.dbagnets.backend.sse.SseEmitterService;
import com.dbagnets.backend.sse.SseEvents;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Orchestrates one cascading insert run end-to-end:
 *
 * <ol>
 *   <li>Resolves the user's leaf-entity choices into a full {@link CascadePlan} (parents
 *       included, topologically ordered).</li>
 *   <li>Persists an {@link InsertRun} + one {@link InsertResult} per {@code (entity × database)}
 *       phase, all initially {@code PENDING}.</li>
 *   <li>Schedules background execution: generates the entire dataset once via
 *       {@link CascadingRecordGenerator} (parents first, FK columns filled from
 *       {@link PrimaryKeyRegistry}), then dispatches one coordinator per database to the worker
 *       pool. Each coordinator runs the entities sequentially in cascade order on that database,
 *       so foreign-key constraints are satisfied.</li>
 *   <li>Aggregates the per-phase outcomes into an overall run status.</li>
 * </ol>
 *
 * <p>Thread-safety:
 * <ul>
 *   <li>Background tasks live outside the Spring web scope; every JPA read/write goes through
 *       {@link TransactionTemplate} for a fresh session.</li>
 *   <li>Only immutable records ({@link RunSnapshot}, {@link DatabaseTarget}, the generator's
 *       {@code CascadeData}) cross thread boundaries; JPA entities never do.</li>
 * </ul>
 */
@Service
@Slf4j
public class InsertOrchestrator {

    private final BenchmarkRepository benchmarkRepository;
    private final BenchmarkDatabaseRepository databaseRepository;
    private final InsertRunRepository runRepository;
    private final InsertResultRepository resultRepository;
    private final LogicalSchemaLoader schemaLoader;
    private final CascadingRecordGenerator cascadingGenerator;
    private final InsertStrategyFactory strategyFactory;
    private final DockerService dockerService;
    private final SseEmitterService sseEmitterService;
    private final BaselineSizeService baselineSizeService;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper;

    private final ExecutorService coordinator = Executors.newCachedThreadPool();
    /** Virtual-thread-per-task executor — perfect for the (mostly blocking) per-DB insert work. */
    private final ExecutorService perDb = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());

    public InsertOrchestrator(
        BenchmarkRepository benchmarkRepository,
        BenchmarkDatabaseRepository databaseRepository,
        InsertRunRepository runRepository,
        InsertResultRepository resultRepository,
        LogicalSchemaLoader schemaLoader,
        CascadingRecordGenerator cascadingGenerator,
        InsertStrategyFactory strategyFactory,
        DockerService dockerService,
        SseEmitterService sseEmitterService,
        BaselineSizeService baselineSizeService,
        PlatformTransactionManager txManager,
        ObjectMapper objectMapper
    ) {
        this.benchmarkRepository = benchmarkRepository;
        this.databaseRepository = databaseRepository;
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
        this.schemaLoader = schemaLoader;
        this.cascadingGenerator = cascadingGenerator;
        this.strategyFactory = strategyFactory;
        this.dockerService = dockerService;
        this.sseEmitterService = sseEmitterService;
        this.baselineSizeService = baselineSizeService;
        this.txTemplate = new TransactionTemplate(txManager);
        this.objectMapper = objectMapper;
    }

    /* ===================== Request validation + persistence ===================== */

    @Transactional
    public InsertRunResponse startRun(String benchmarkId, StartInsertRunRequest request) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId)
            .orElseThrow(() -> new NoSuchElementException("Benchmark not found: " + benchmarkId));

        LogicalSchema schema = schemaLoader.load(benchmark)
            .orElseThrow(() -> new IllegalStateException("Benchmark has no logical schema yet"));

        CascadePlan plan = buildCascadePlan(schema, request.entities());

        List<BenchmarkDatabase> selected = collectSelectedDatabases(benchmark, request.databaseIds());
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("No matching databases selected on this benchmark");
        }

        String cascadeJson = encodePlanAsJson(plan);
        String leafEntityName = plan.orderedEntities().get(plan.orderedEntities().size() - 1).name();
        int totalRecords = plan.orderedEntities().stream().mapToInt(EntityNode::recordCount).sum();

        InsertRun run = new InsertRun(
            benchmark,
            leafEntityName,
            totalRecords,
            request.mode(),
            request.batchSize(),
            request.effectiveWorkerCount(),
            cascadeJson
        );
        for (BenchmarkDatabase db : selected) {
            for (EntityNode node : plan.orderedEntities()) {
                run.addResult(new InsertResult(db.getId(), db.getDbName(), node.name()));
            }
        }
        runRepository.save(run);

        String runId = run.getId();
        InsertRunResponse response = InsertRunResponse.from(run);
        coordinator.submit(() -> orchestrate(runId));
        return response;
    }

    @Transactional(readOnly = true)
    public InsertRunResponse getRun(String runId) {
        InsertRun run = runRepository.findById(runId)
            .orElseThrow(() -> new NoSuchElementException("Insert run not found: " + runId));
        return InsertRunResponse.from(run);
    }

    @Transactional(readOnly = true)
    public List<InsertRunResponse> listRuns(String benchmarkId) {
        return runRepository.findByBenchmarkIdOrderByCreatedAtDesc(benchmarkId).stream()
            .map(InsertRunResponse::from)
            .toList();
    }

    /* ===================== Background orchestration ===================== */

    private void orchestrate(String runId) {
        try {
            RunSnapshot snapshot = loadSnapshot(runId);
            updateRunStatus(runId, InsertStatus.RUNNING);

            // Freeze the baseline RIGHT NOW for every DB in this run — but ONLY on the very first
            // insert run for that DB. Subsequent runs leave the baseline alone, otherwise the
            // cyan "engine" segment would absorb the data from the previous run and each new run
            // would appear to insert almost nothing (we'd be measuring the delta against an
            // ever-growing reference point instead of against the true engine+schema footprint).
            for (String dbId : snapshot.databaseIds()) {
                try {
                    baselineSizeService.captureForFirstInsertOnly(dbId);
                } catch (Exception ex) {
                    log.warn("Pre-insert baseline freeze failed for database {}: {}", dbId, ex.getMessage());
                }
            }

            log.info("Cascade run {}: generating data for {} entities (total {} records)",
                runId, snapshot.plan().orderedEntities().size(),
                snapshot.plan().orderedEntities().stream().mapToInt(EntityNode::recordCount).sum());
            CascadingRecordGenerator.CascadeData data = cascadingGenerator.generate(
                snapshot.plan(), new PrimaryKeyRegistry());

            List<Future<?>> futures = new ArrayList<>(snapshot.databaseIds().size());
            for (String databaseId : snapshot.databaseIds()) {
                futures.add(perDb.submit(() -> runForDatabase(runId, databaseId, snapshot, data)));
            }
            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception e) {
                    log.warn("Per-DB cascade task failed for run {}", runId, e);
                }
            }
            finalizeRun(runId);
        } catch (Exception e) {
            log.error("Insert orchestration failed for run {}", runId, e);
            failRun(runId, e.getMessage());
        }
    }

    private void runForDatabase(
        String runId,
        String databaseId,
        RunSnapshot snapshot,
        CascadingRecordGenerator.CascadeData data
    ) {
        DatabaseTarget target = txTemplate.execute(status ->
            databaseRepository.findById(databaseId).map(DatabaseTarget::from).orElse(null));
        if (target == null) {
            failAllPhasesForDb(runId, databaseId, snapshot, "Database deleted before run started");
            return;
        }
        if (target.status() != DatabaseStatus.RUNNING) {
            failAllPhasesForDb(runId, databaseId, snapshot,
                "Database is " + target.status() + " — must be RUNNING to accept inserts");
            return;
        }
        if (target.containerId() == null || !dockerService.isContainerRunning(target.containerId())) {
            markDatabaseStopped(target.id(), target.dbName());
            failAllPhasesForDb(runId, databaseId, snapshot,
                "Container for '" + target.dbName() + "' is not running anymore. Click 'Redeploy' on the benchmark page to bring it back.");
            return;
        }

        DatabaseInsertStrategy strategy = strategyFactory.create(target.dbName());

        for (EntityNode node : snapshot.plan().orderedEntities()) {
            String resultId = snapshot.resultIdOf(databaseId, node.name());
            if (resultId == null) {
                log.warn("Missing result row for ({}, {}) — skipping", databaseId, node.name());
                continue;
            }
            executePhase(runId, resultId, node, snapshot, data, target, strategy);
        }
    }

    private void executePhase(
        String runId,
        String resultId,
        EntityNode node,
        RunSnapshot snapshot,
        CascadingRecordGenerator.CascadeData data,
        DatabaseTarget target,
        DatabaseInsertStrategy strategy
    ) {
        List<GeneratedRecord> records = data.recordsFor(node.name());
        if (records.isEmpty()) {
            markResult(runId, resultId, InsertStatus.SUCCESS, 0L, 0, 0.0, null,
                Instant.now(), Instant.now());
            return;
        }

        Instant startedAt = Instant.now();
        markResult(runId, resultId, InsertStatus.RUNNING, null, null, null, null, startedAt, null);

        InsertContext context = new InsertContext(
            target.containerId(),
            target.dbName(),
            target.dbVersion(),
            "localhost",
            target.hostPort(),
            node.name(),
            List.copyOf(node.entity().attributesOrEmpty()),
            records,
            snapshot.mode(),
            snapshot.batchSize() == null ? records.size() : snapshot.batchSize(),
            snapshot.workerCount()
        );

        BatchProgressCallback progress = (batchIndex, batchCount, recordsDone) ->
            sseEmitterService.sendEvent(runId, SseEvents.EVENT_INSERT_BATCH_PROGRESS, new BatchProgressEvent(
                runId, resultId, target.id(), node.name(),
                batchIndex, batchCount, recordsDone, records.size()));

        InsertOutcome outcome;
        try {
            outcome = strategy.insert(dockerService, context, progress);
        } catch (Exception e) {
            log.warn("Strategy threw for {} / {}", target.dbName(), node.name(), e);
            outcome = InsertOutcome.failure("Strategy exception: " + e.getMessage(), 0);
        }

        Double throughput = (outcome.success() && outcome.durationMs() > 0)
            ? 1000.0 * outcome.recordsInserted() / outcome.durationMs()
            : 0.0;

        markResult(
            runId,
            resultId,
            outcome.success() ? InsertStatus.SUCCESS : InsertStatus.FAILED,
            outcome.durationMs(),
            outcome.recordsInserted(),
            throughput,
            outcome.errorMessage(),
            startedAt,
            Instant.now()
        );

        // Nudge the benchmark page to refetch the size chart immediately — far more responsive
        // than waiting up to 30s for the next poll. Sent per phase, per DB, so each chart segment
        // updates independently as its data lands on disk.
        sseEmitterService.sendEvent(snapshot.benchmarkId(), SseEvents.EVENT_DATABASE_SIZE_DIRTY, Map.of(
            SseEvents.PAYLOAD_BENCHMARK_ID, snapshot.benchmarkId(),
            SseEvents.PAYLOAD_DATABASE_ID, target.id(),
            SseEvents.PAYLOAD_ENTITY_NAME, node.name()
        ));
    }

    private void failAllPhasesForDb(String runId, String databaseId, RunSnapshot snapshot, String reason) {
        for (EntityNode node : snapshot.plan().orderedEntities()) {
            String resultId = snapshot.resultIdOf(databaseId, node.name());
            if (resultId == null) continue;
            markResult(runId, resultId, InsertStatus.FAILED, null, null, null, reason,
                Instant.now(), Instant.now());
        }
    }

    /* ===================== Snapshot loading ===================== */

    private RunSnapshot loadSnapshot(String runId) {
        return txTemplate.execute(status -> {
            InsertRun run = runRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Insert run vanished: " + runId));
            CascadePlan plan = decodePlanFromJson(run.getCascadeJson(), run);
            Map<String, Map<String, String>> resultIds = new HashMap<>();
            List<String> databaseIds = new ArrayList<>();
            for (InsertResult r : run.getResults()) {
                resultIds.computeIfAbsent(r.getDatabaseId(), k -> new HashMap<>())
                    .put(normalizeEntityName(r.getEntityName()), r.getId());
                if (!databaseIds.contains(r.getDatabaseId())) databaseIds.add(r.getDatabaseId());
            }
            return new RunSnapshot(
                runId,
                run.getBenchmark().getId(),
                plan,
                run.getMode(),
                run.getBatchSize(),
                run.getWorkerCount() == null ? 1 : run.getWorkerCount(),
                List.copyOf(databaseIds),
                resultIds
            );
        });
    }

    private CascadePlan decodePlanFromJson(String cascadeJson, InsertRun run) {
        // For now we trust the live schema instead of replaying the snapshot — keeps things simple
        // and avoids requiring round-trip-safe (de)serialization of LogicalEntity in this slice.
        // Slice E adds a faithful snapshot replay.
        LogicalSchema schema = schemaLoader.load(run.getBenchmark())
            .orElseThrow(() -> new IllegalStateException("Schema vanished"));
        try {
            Map<?, ?> tree = objectMapper.readValue(cascadeJson, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nodes = (List<Map<String, Object>>) tree.get("entities");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> edges = (List<Map<String, Object>>) tree.get("edges");

            List<String> leafNames = new ArrayList<>();
            Map<String, Integer> leafCounts = new LinkedHashMap<>();
            for (Map<String, Object> n : nodes) {
                boolean leaf = Boolean.TRUE.equals(n.get("leaf"));
                if (leaf) {
                    String name = (String) n.get("name");
                    leafNames.add(name);
                    leafCounts.put(name, ((Number) n.get("recordCount")).intValue());
                }
            }
            if (leafNames.isEmpty()) {
                String name = (String) nodes.get(nodes.size() - 1).get("name");
                leafNames.add(name);
                leafCounts.put(name, ((Number) nodes.get(nodes.size() - 1).get("recordCount")).intValue());
            }
            List<EdgeRatioOverride> overrides = new ArrayList<>();
            for (Map<String, Object> e : edges) {
                overrides.add(new EdgeRatioOverride(
                    (String) e.get("childEntity"),
                    (String) e.get("parentEntity"),
                    ((Number) e.get("ratio")).doubleValue()
                ));
            }
            return CascadeResolver.resolve(schema, leafNames, overrides, leafCounts);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to decode cascade plan", ex);
        }
    }

    /* ===================== Helpers ===================== */

    private CascadePlan buildCascadePlan(LogicalSchema schema, List<EntityCascadeChoice> entities) {
        List<String> leafNames = entities.stream().map(EntityCascadeChoice::entityName).toList();
        Map<String, Integer> leafCounts = new LinkedHashMap<>();
        List<EdgeRatioOverride> overrides = new ArrayList<>();
        for (EntityCascadeChoice c : entities) {
            leafCounts.put(c.entityName(), c.recordCount());
            for (EdgeRatio r : c.edgeRatiosOrEmpty()) {
                overrides.add(new EdgeRatioOverride(r.childEntity(), r.parentEntity(), r.ratio()));
            }
        }
        return CascadeResolver.resolve(schema, leafNames, overrides, leafCounts);
    }

    private String encodePlanAsJson(CascadePlan plan) {
        List<Map<String, Object>> entities = new ArrayList<>(plan.orderedEntities().size());
        List<String> leafNames = leafEntityNames(plan);
        for (EntityNode node : plan.orderedEntities()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", node.name());
            m.put("recordCount", node.recordCount());
            m.put("parents", node.parents());
            m.put("leaf", leafNames.contains(node.name()));
            entities.add(m);
        }
        List<Map<String, Object>> edges = new ArrayList<>(plan.edges().size());
        plan.edges().forEach(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("childEntity", e.childEntity());
            m.put("parentEntity", e.parentEntity());
            m.put("cardinality", e.cardinality().name());
            m.put("ratio", e.ratio());
            edges.add(m);
        });
        try {
            return objectMapper.writeValueAsString(Map.of("entities", entities, "edges", edges));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode cascade plan", e);
        }
    }

    private static List<String> leafEntityNames(CascadePlan plan) {
        List<String> nonLeaf = new ArrayList<>();
        plan.edges().forEach(e -> nonLeaf.add(e.parentEntity().toLowerCase()));
        List<String> leaves = new ArrayList<>();
        for (EntityNode node : plan.orderedEntities()) {
            if (!nonLeaf.contains(node.name().toLowerCase())) leaves.add(node.name());
        }
        return leaves;
    }

    private List<BenchmarkDatabase> collectSelectedDatabases(Benchmark benchmark, List<String> requestedIds) {
        List<BenchmarkDatabase> result = new ArrayList<>();
        for (BenchmarkDatabase db : benchmark.getDatabases()) {
            if (requestedIds.contains(db.getId())) result.add(db);
        }
        return result;
    }

    private void markDatabaseStopped(String databaseId, String dbName) {
        txTemplate.executeWithoutResult(status -> {
            BenchmarkDatabase db = databaseRepository.findById(databaseId).orElse(null);
            if (db == null) return;
            db.setStatus(DatabaseStatus.STOPPED);
            db.setErrorMessage("Container '" + dbName + "' is not running anymore");
            databaseRepository.save(db);
            String benchmarkId = db.getBenchmark().getId();
            sseEmitterService.sendEvent(benchmarkId, SseEvents.EVENT_DATABASE_STATUS, Map.of(
                SseEvents.PAYLOAD_BENCHMARK_ID, benchmarkId,
                SseEvents.PAYLOAD_DATABASE_ID, databaseId,
                SseEvents.PAYLOAD_STATUS, DatabaseStatus.STOPPED.name(),
                SseEvents.PAYLOAD_ERROR_MESSAGE, db.getErrorMessage()
            ));
        });
    }

    private void markResult(
        String runId, String resultId, InsertStatus status,
        Long durationMs, Integer recordsInserted, Double throughputRps, String errorMessage,
        Instant startedAt, Instant finishedAt
    ) {
        InsertResultResponse payload = txTemplate.execute(txStatus -> {
            InsertResult result = resultRepository.findById(resultId).orElseThrow();
            result.setStatus(status);
            if (startedAt != null) result.setStartedAt(startedAt);
            else if (result.getStartedAt() == null) result.setStartedAt(Instant.now());
            result.setFinishedAt(finishedAt);
            result.setDurationMs(durationMs);
            result.setRecordsInserted(recordsInserted);
            result.setThroughputRps(throughputRps);
            result.setErrorMessage(errorMessage);
            resultRepository.save(result);
            return InsertResultResponse.from(result);
        });
        sseEmitterService.sendEvent(runId, SseEvents.EVENT_INSERT_RESULT_STATUS, payload);
    }

    private void updateRunStatus(String runId, InsertStatus status) {
        txTemplate.executeWithoutResult(txStatus -> {
            InsertRun run = runRepository.findById(runId).orElseThrow();
            run.setStatus(status);
            if (status == InsertStatus.SUCCESS || status == InsertStatus.FAILED) {
                run.setFinishedAt(Instant.now());
            }
            runRepository.save(run);
        });
        sseEmitterService.sendEvent(runId, SseEvents.EVENT_INSERT_RUN_STATUS, Map.of(
            SseEvents.PAYLOAD_RUN_ID, runId,
            SseEvents.PAYLOAD_STATUS, status.name()
        ));
    }

    private void finalizeRun(String runId) {
        InsertStatus next = txTemplate.execute(status -> {
            InsertRun run = runRepository.findById(runId).orElseThrow();
            boolean anyFailed = run.getResults().stream().anyMatch(r -> r.getStatus() == InsertStatus.FAILED);
            boolean allSuccess = run.getResults().stream().allMatch(r -> r.getStatus() == InsertStatus.SUCCESS);
            if (allSuccess) return InsertStatus.SUCCESS;
            if (anyFailed) return InsertStatus.FAILED;
            return InsertStatus.SUCCESS;
        });
        updateRunStatus(runId, next);
    }

    private void failRun(String runId, String reason) {
        List<String> pendingIds = txTemplate.execute(status -> {
            InsertRun run = runRepository.findById(runId).orElse(null);
            if (run == null) return List.<String>of();
            return run.getResults().stream()
                .filter(r -> r.getStatus() == InsertStatus.PENDING || r.getStatus() == InsertStatus.RUNNING)
                .map(InsertResult::getId)
                .toList();
        });
        for (String resultId : pendingIds) {
            markResult(runId, resultId, InsertStatus.FAILED, 0L, 0, 0.0, "Run aborted: " + reason,
                Instant.now(), Instant.now());
        }
        updateRunStatus(runId, InsertStatus.FAILED);
    }

    private static String normalizeEntityName(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    /* ===================== Immutable cross-thread carriers ===================== */

    private record RunSnapshot(
        String runId,
        String benchmarkId,
        CascadePlan plan,
        InsertMode mode,
        Integer batchSize,
        int workerCount,
        List<String> databaseIds,
        Map<String, Map<String, String>> resultIdsByDbAndEntity
    ) {
        String resultIdOf(String databaseId, String entityName) {
            Map<String, String> m = resultIdsByDbAndEntity.get(databaseId);
            return m == null ? null : m.get(normalizeEntityName(entityName));
        }
    }

    private record DatabaseTarget(
        String id, String dbName, String dbVersion,
        String containerId, Integer hostPort, DatabaseStatus status
    ) {
        static DatabaseTarget from(BenchmarkDatabase db) {
            return new DatabaseTarget(db.getId(), db.getDbName(), db.getDbVersion(),
                db.getContainerId(), db.getHostPort(), db.getStatus());
        }
    }
}
