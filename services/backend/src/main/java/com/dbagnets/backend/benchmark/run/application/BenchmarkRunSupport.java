package com.dbagnets.backend.benchmark.run.application;

import com.dbagnets.backend.benchmark.run.persistence.BenchmarkResult;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkResultRepository;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkRun;
import com.dbagnets.backend.benchmark.run.persistence.BenchmarkRunRepository;
import com.dbagnets.backend.benchmark.run.api.dto.EntityRunRequest;
import com.dbagnets.backend.benchmark.run.internal.CascadePreviewService;
import com.dbagnets.backend.benchmark.run.internal.RunPreview;
import com.dbagnets.backend.benchmark.run.persistence.OperationType;
import com.dbagnets.backend.benchmark.run.persistence.RunStatus;
import com.dbagnets.backend.engine.registry.EntityIdRegistry;
import com.dbagnets.backend.engine.driver.api.DriverResolution;
import com.dbagnets.backend.engine.driver.api.EngineDriver;
import com.dbagnets.backend.engine.driver.EngineDriverFactory;
import com.dbagnets.backend.engine.resource.ResourceMetricsSummary;
import com.dbagnets.backend.engine.resource.ResourceSample;
import com.dbagnets.backend.engine.schema.EmbeddingMap;
import com.dbagnets.backend.engine.schema.EmbeddingMappingLoader;
import com.dbagnets.backend.engine.schema.LogicalSchema;
import com.dbagnets.backend.engine.schema.LogicalSchemaLoader;
import com.dbagnets.backend.engine.timing.TimedOperation;
import com.dbagnets.backend.infrastructure.persistence.BenchmarkRepository;
import com.dbagnets.backend.shared.entity.Benchmark;
import com.dbagnets.backend.shared.entity.BenchmarkDatabase;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class BenchmarkRunSupport {

    private static final TypeReference<List<ResourceSample>> RESOURCE_SAMPLES_TYPE = new TypeReference<>() {};

    private final BenchmarkResultRepository resultRepository;
    private final BenchmarkRunRepository runRepository;
    private final BenchmarkRepository benchmarkRepository;
    private final LogicalSchemaLoader schemaLoader;
    private final EmbeddingMappingLoader embeddingMappingLoader;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final EngineDriverFactory driverFactory;
    private final EntityIdRegistry entityIdRegistry;
    private final CascadePreviewService cascadePreviewService;

    public LogicalSchema loadSchema(Benchmark benchmark) {
        if (benchmark.getLogicalSchema() == null) {
            throw new IllegalStateException("Benchmark " + benchmark.getId() + " has no logical schema");
        }
        return schemaLoader.parse(benchmark.getLogicalSchema());
    }

    public LogicalSchema loadSchema(String benchmarkId) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId)
                .orElseThrow(() -> new NoSuchElementException("Benchmark not found: " + benchmarkId));
        return loadSchema(benchmark);
    }

    @Transactional(readOnly = true)
    public List<ResourceSample> loadResourceTimeline(String runId, String resultId, OperationType expected) {
        BenchmarkRun run = runRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Run not found: " + runId));
        if (run.getOperationType() != expected) {
            throw new IllegalArgumentException("Run " + runId + " is not a " + expected.name() + " run");
        }
        BenchmarkResult result = resultRepository.findById(resultId)
                .orElseThrow(() -> new NoSuchElementException("Result not found: " + resultId));
        if (!result.getRun().getId().equals(runId)) {
            throw new IllegalArgumentException("Result " + resultId + " does not belong to run " + runId);
        }
        String json = result.getResourceSamplesJson();
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, RESOURCE_SAMPLES_TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }

    public String resultIdToDatabaseId(String resultId) {
        return resultRepository.findById(resultId).orElseThrow().getDatabaseId();
    }

    public String serializeQuietly(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return null;
        }
    }

    public void markStarted(String resultId, Consumer<BenchmarkResult> broadcast) {
        transactionTemplate.executeWithoutResult(s -> {
            BenchmarkResult r = resultRepository.findById(resultId).orElseThrow();
            r.setStatus(RunStatus.RUNNING);
            r.setStartedAt(Instant.now());
            resultRepository.save(r);
            broadcast.accept(r);
        });
    }

    public void markFailed(String resultId, String message, Consumer<BenchmarkResult> broadcast) {
        transactionTemplate.executeWithoutResult(s -> {
            BenchmarkResult r = resultRepository.findById(resultId).orElseThrow();
            r.setStatus(RunStatus.FAILED);
            r.setFinishedAt(Instant.now());
            r.setErrorMessage(message);
            resultRepository.save(r);
            broadcast.accept(r);
        });
    }

    public void markSkipped(String resultId, String reason, Consumer<BenchmarkResult> broadcast) {
        transactionTemplate.executeWithoutResult(s -> {
            BenchmarkResult r = resultRepository.findById(resultId).orElseThrow();
            r.setStatus(RunStatus.SKIPPED);
            r.setStartedAt(Instant.now());
            r.setFinishedAt(Instant.now());
            r.setErrorMessage(reason);
            resultRepository.save(r);
            broadcast.accept(r);
        });
    }

    public record EntityRunContext(Benchmark benchmark, List<String> selectedIds, RunPreview preview) {
    }

    public EntityRunContext prepareEntityRunContext(String benchmarkId, EntityRunRequest request) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow(() -> new java.util.NoSuchElementException("Benchmark not found: " + benchmarkId));
        LogicalSchema schema = loadSchema(benchmark);
        int sampleSize = orDefault(request.sampleSize(), 100);
        List<String> selectedIds = entityIdRegistry.selectLogicalIds(benchmarkId, request.entityName(), sampleSize, request.strategyOrDefault());
        RunPreview preview = cascadePreviewService.build(benchmarkId, schema, request.entityName(), selectedIds.size(), Boolean.TRUE.equals(request.includeChildren()));
        return new EntityRunContext(benchmark, selectedIds, preview);
    }

    public BenchmarkRun createEntityRun(String benchmarkId, OperationType type, String entityName, List<String> databaseIds, Benchmark benchmark, List<String> selectedIds, RunPreview preview, Object requestPayload, Consumer<BenchmarkRun> broadcast) {
        BenchmarkRun run = new BenchmarkRun(benchmarkId, type);
        run.setEntityName(entityName);
        run.setRecordCount((long) selectedIds.size());
        run.setConfigJson(serializeQuietly(requestPayload));
        run.setSelectedIdsJson(serializeQuietly(selectedIds));
        run.setCascadePreviewJson(serializeQuietly(preview));

        for (String databaseId : databaseIds) {
            BenchmarkDatabase db = findDatabase(benchmark, databaseId);
            BenchmarkResult result = new BenchmarkResult(db.getId(), db.getDbName());
            result.setEntityName(entityName);
            run.addResult(result);
        }
        runRepository.save(run);
        broadcast.accept(run);
        return run;
    }

    public record DatabaseContext(BenchmarkDatabase db, EngineDriver driver, EmbeddingMap embeddings) {}

    public BenchmarkDatabase resolveDatabase(String benchmarkId, String resultId) {
        return java.util.Objects.requireNonNull(transactionTemplate.execute(s ->
                benchmarkRepository.findById(benchmarkId).orElseThrow()
                        .getDatabases().stream()
                        .filter(x -> x.getId().equals(resultIdToDatabaseId(resultId)))
                        .findFirst()
                        .orElseThrow()));
    }

    public Optional<DatabaseContext> resolveDatabaseContext(
            String benchmarkId, String resultId, Consumer<BenchmarkResult> skipBroadcast) {
        BenchmarkDatabase db = resolveDatabase(benchmarkId, resultId);
        Optional<EngineDriver> driverOpt = resolveDriver(db, resultId, skipBroadcast);
        if (driverOpt.isEmpty()) return Optional.empty();
        EmbeddingMap embeddings = EmbeddingMap.from(embeddingMappingLoader.parse(db.getEmbeddingMappings()));
        return Optional.of(new DatabaseContext(db, driverOpt.get(), embeddings));
    }

    public Optional<List<EntityIdRegistry.RegistryEntry>> resolveTargets(
            String databaseId, String entityName, List<String> selectedLogicalIds,
            String resultId, Consumer<BenchmarkResult> skipBroadcast) {
        List<EntityIdRegistry.RegistryEntry> targets = entityIdRegistry.lookupEntries(databaseId, entityName, selectedLogicalIds);
        if (targets.isEmpty()) {
            String dbName = resultRepository.findById(resultId).map(BenchmarkResult::getDbName).orElse(databaseId);
            markSkipped(resultId, "No matching IDs in registry for " + dbName, skipBroadcast);
            return Optional.empty();
        }
        return Optional.of(targets);
    }

    public Optional<EngineDriver> resolveDriver(BenchmarkDatabase db, String resultId, Consumer<BenchmarkResult> skipBroadcast) {
        DriverResolution resolution = driverFactory.resolve(db);
        if (resolution instanceof DriverResolution.Skipped(String reason)) {
            markSkipped(resultId, reason, skipBroadcast);
            return Optional.empty();
        }
        return Optional.of(((DriverResolution.Resolved) resolution).driver());
    }

    public void persistResourceSummary(String resultId, ResourceMetricsSummary summary, Consumer<BenchmarkResult> broadcast) {
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
            broadcast.accept(r);
        });
    }

    public void finalizeRun(String runId, Consumer<BenchmarkRun> broadcast) {
        transactionTemplate.executeWithoutResult(s -> {
            BenchmarkRun run = runRepository.findById(runId).orElseThrow();
            run.setFinishedAt(Instant.now());
            run.setStatus(aggregateStatus(run.getResults()));
            runRepository.save(run);
            broadcast.accept(run);
        });
    }

    public static BenchmarkDatabase findDatabase(Benchmark benchmark, String databaseId) {
        return benchmark.getDatabases().stream().filter(d -> d.getId().equals(databaseId)).findFirst().orElseThrow(() -> new NoSuchElementException("Database not in benchmark: " + databaseId));
    }

    public static RunStatus aggregateStatus(List<BenchmarkResult> results) {
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

    public static int orDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    public static TimedOperation mergeTimedOps(List<TimedOperation> ops) {
        if (ops.size() == 1) return ops.getFirst();
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
        long[] mergedSamples = new long[totalSamples];
        int offset = 0;
        for (TimedOperation op : ops) {
            long[] samples = op.sampleDbTimeNs();
            System.arraycopy(samples, 0, mergedSamples, offset, samples.length);
            offset += samples.length;
        }
        return TimedOperation.builder().dbTimeNs(dbTimeNs).wireTimeNs(wireTimeNs).rowsAffected(rowsAffected).sampleDbTimeNs(mergedSamples).build();
    }
}