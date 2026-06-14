package com.dbagnets.backend.service;

import com.dbagnets.backend.benchmark.bundle.BenchmarkBundleService;
import com.dbagnets.backend.benchmark.bundle.BenchmarkBundleService.ParsedBundle;
import com.dbagnets.backend.benchmark.bundle.BundleManifest;
import com.dbagnets.backend.benchmark.driver.ConnectionCacheRegistry;
import com.dbagnets.backend.benchmark.registry.EntityIdRegistry;
import com.dbagnets.backend.benchmark.size.DataSizeProbe;
import com.dbagnets.backend.client.ScriptCreatorClient;
import com.dbagnets.backend.client.ScriptCreatorRequest.TargetRequest;
import com.dbagnets.backend.client.ScriptCreatorResponse;
import com.dbagnets.backend.client.ScriptCreatorResponse.ScriptResult;
import com.dbagnets.backend.docker.ContainerSpec;
import com.dbagnets.backend.docker.DockerService;
import com.dbagnets.backend.docker.ScriptExecutor;
import com.dbagnets.backend.entity.*;
import com.dbagnets.backend.model.BenchmarkResponse;
import com.dbagnets.backend.model.CreateBenchmarkRequest;
import com.dbagnets.backend.repository.BenchmarkDatabaseRepository;
import com.dbagnets.backend.repository.BenchmarkRepository;
import com.dbagnets.backend.sse.SseEmitterService;
import com.dbagnets.backend.sse.SseEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
@RequiredArgsConstructor
@Slf4j
public class BenchmarkService {

    private static final long CONTAINER_MEMORY_MB = 2048;

    private final BenchmarkRepository benchmarkRepository;
    private final BenchmarkDatabaseRepository databaseRepository;
    private final ScriptCreatorClient scriptCreatorClient;
    private final DockerService dockerService;
    private final ScriptExecutor scriptExecutor;
    private final SseEmitterService sseEmitterService;
    private final ObjectMapper objectMapper;
    private final DataSizeProbe dataSizeProbe;
    private final ConnectionCacheRegistry connectionCacheRegistry;
    private final EntityIdRegistry entityIdRegistry;
    private final BenchmarkBundleService bundleService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private static final String HOST_ADDRESS = "127.0.0.1";

    @Transactional
    public BenchmarkResponse createBenchmark(CreateBenchmarkRequest request, User user) {
        Benchmark benchmark = new Benchmark(request.topic(), user, request.depth());
        for (CreateBenchmarkRequest.DatabaseTarget target : request.databases()) {
            DatabaseType dbType = DatabaseType.valueOf(target.dbType().toUpperCase());
            BenchmarkDatabase db = new BenchmarkDatabase(dbType, target.dbName(), target.dbVersion());
            benchmark.addDatabase(db);
        }

        benchmarkRepository.save(benchmark);
        log.info("Created benchmark {} with {} databases", benchmark.getId(), request.databases().size());

        executor.submit(() -> orchestrateBenchmark(benchmark.getId()));

        return BenchmarkResponse.from(benchmark);
    }

    public void redeployBenchmark(String benchmarkId) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
        List<BenchmarkDatabase> redeployableDbs = benchmark.getDatabases().stream()
                .filter(db -> db.getScript() != null
                        && db.getStatus() != DatabaseStatus.RUNNING
                        && db.getStatus() != DatabaseStatus.PENDING
                        && db.getStatus() != DatabaseStatus.SCRIPT_GENERATING
                        && db.getStatus() != DatabaseStatus.CONTAINER_STARTING
                        && db.getStatus() != DatabaseStatus.INITIALIZING)
                .toList();

        if (redeployableDbs.isEmpty()) {
            throw new RuntimeException("No databases available for redeployment");
        }

        log.info("Redeploying benchmark {} with {} databases", benchmarkId, redeployableDbs.size());

        for (BenchmarkDatabase db : redeployableDbs) {
            cleanupContainer(db);
            entityIdRegistry.evictAllForDatabase(db.getId());
            db.setStatus(DatabaseStatus.SCRIPT_READY);
            db.setErrorMessage(null);
            databaseRepository.save(db);
        }

        updateBenchmarkStatus(benchmarkId, BenchmarkStatus.STARTING_CONTAINERS);

        executor.submit(() -> {
            try {
                startContainers(benchmarkId);
                initializeDatabases(benchmarkId);
                finalizeBenchmark(benchmarkId);
            } catch (ObjectOptimisticLockingFailureException e) {
                log.info("Benchmark {} modified concurrently during redeploy, aborting silently", benchmarkId);
            } catch (Exception e) {
                log.error("Redeploy failed for benchmark {}", benchmarkId, e);
                try {
                    failRemainingDatabases(benchmarkId, e.getMessage());
                    updateBenchmarkStatus(benchmarkId, BenchmarkStatus.FAILED);
                } catch (ObjectOptimisticLockingFailureException raceWithCancel) {
                    log.info("Benchmark {} cancelled while marking as FAILED, skipping", benchmarkId);
                }
            }
        });
    }

    public void redeployDatabase(String benchmarkId, String databaseId) {
        BenchmarkDatabase db = databaseRepository.findById(databaseId).orElseThrow();

        if (db.getScript() == null) {
            throw new RuntimeException("No script available for redeploy");
        }

        log.info("Redeploying database {} ({}) in benchmark {}", db.getDbName(), databaseId, benchmarkId);

        cleanupContainer(db);
        entityIdRegistry.evictAllForDatabase(db.getId());
        db.setStatus(DatabaseStatus.SCRIPT_READY);
        db.setErrorMessage(null);
        databaseRepository.save(db);
        updateDatabaseStatus(databaseId, DatabaseStatus.SCRIPT_READY);

        updateBenchmarkStatus(benchmarkId, BenchmarkStatus.STARTING_CONTAINERS);

        executor.submit(() -> {
            try {
                startSingleContainer(benchmarkId, databaseId);
                var refreshedDb = databaseRepository.findById(databaseId).orElseThrow();
                if (refreshedDb.getStatus() == DatabaseStatus.INITIALIZING) {
                    initializeSingleDatabase(benchmarkId, databaseId);
                }
                finalizeBenchmark(benchmarkId);
            } catch (ObjectOptimisticLockingFailureException e) {
                log.info("Database {} modified concurrently during redeploy, aborting silently", databaseId);
            } catch (Exception e) {
                log.error("Redeploy failed for database {} in benchmark {}", databaseId, benchmarkId, e);
                try {
                    Optional<BenchmarkDatabase> failedOpt = databaseRepository.findById(databaseId);
                    if (failedOpt.isPresent()) {
                        BenchmarkDatabase failedDb = failedOpt.get();
                        failedDb.setStatus(DatabaseStatus.FAILED);
                        failedDb.setErrorMessage(e.getMessage());
                        databaseRepository.save(failedDb);
                    }
                    finalizeBenchmark(benchmarkId);
                } catch (ObjectOptimisticLockingFailureException raceWithCancel) {
                    log.info("Database {} cancelled while marking as FAILED, skipping", databaseId);
                }
            }
        });
    }

    @Transactional
    public void hardResetBenchmark(String benchmarkId) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
        log.info("HARD RESET requested for benchmark {} — wiping {} containers and volumes",
                benchmarkId, benchmark.getDatabases().size());

        String benchmarkPrefix = "benchmark-" + benchmarkId.substring(0, 8) + "-";
        for (BenchmarkDatabase db : benchmark.getDatabases()) {
            if (db.getContainerId() != null) {
                try {
                    dockerService.stopContainer(db.getContainerId());
                } catch (Exception ignored){}
                dockerService.hardRemoveContainer(db.getContainerId());
                db.setContainerId(null);
                db.setHostPort(null);
            }
            dockerService.removeContainersByNamePrefix(benchmarkPrefix + db.getDbName());
            connectionCacheRegistry.evictAll(db.getId());
            dataSizeProbe.invalidate(db.getId());
            entityIdRegistry.evictAllForDatabase(db.getId());
            db.setStatus(DatabaseStatus.SCRIPT_READY);
            db.setErrorMessage(null);
            db.setBaselineSizeBytes(null);
            db.setBaselineRecordedAt(null);
            databaseRepository.save(db);
        }
        updateBenchmarkStatus(benchmarkId, BenchmarkStatus.STARTING_CONTAINERS);

        executor.submit(() -> {
            try {
                startContainers(benchmarkId);
                initializeDatabases(benchmarkId);
                finalizeBenchmark(benchmarkId);
            } catch (Exception e) {
                log.error("Hard reset failed for benchmark {}", benchmarkId, e);
                failRemainingDatabases(benchmarkId, e.getMessage());
                updateBenchmarkStatus(benchmarkId, BenchmarkStatus.FAILED);
            }
        });
    }

    @Transactional
    public void deleteBenchmark(String benchmarkId) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId)
                .orElseThrow(() -> new RuntimeException("Benchmark not found: " + benchmarkId));

        for (BenchmarkDatabase db : benchmark.getDatabases()) {
            cleanupContainer(db);
        }
        entityIdRegistry.evictAllForBenchmark(benchmarkId);

        benchmarkRepository.delete(benchmark);
        log.info("Deleted benchmark {}", benchmarkId);
    }

    @Transactional
    public void deleteDatabase(String benchmarkId, String databaseId) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId)
                .orElseThrow(() -> new RuntimeException("Benchmark not found: " + benchmarkId));
        BenchmarkDatabase db = benchmark.getDatabases().stream()
                .filter(d -> d.getId().equals(databaseId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Database not found: " + databaseId));

        cleanupContainer(db);
        entityIdRegistry.evictAllForDatabase(db.getId());
        benchmark.getDatabases().remove(db);

        if (benchmark.getDatabases().isEmpty()) {
            benchmarkRepository.delete(benchmark);
            log.info("Deleted last database {} and benchmark {}", databaseId, benchmarkId);
        } else {
            benchmarkRepository.save(benchmark);
            finalizeBenchmark(benchmarkId);
            log.info("Deleted database {} from benchmark {}", databaseId, benchmarkId);
        }
    }

    @Transactional
    public void stopDatabase(String benchmarkId, String databaseId) {
        var db = databaseRepository.findById(databaseId).orElseThrow();
        if (db.getStatus() == DatabaseStatus.STOPPED) {
            return;
        }
        if (db.getContainerId() != null) {
            dockerService.stopContainer(db.getContainerId());
        }
        updateDatabaseStatus(databaseId, DatabaseStatus.STOPPED);
        finalizeBenchmark(benchmarkId);
    }

    @Transactional
    public void restartDatabase(String benchmarkId, String databaseId) {
        var db = databaseRepository.findById(databaseId).orElseThrow();
        if (db.getContainerId() != null) {
            dockerService.restartContainer(db.getContainerId());
        }
        updateDatabaseStatus(databaseId, DatabaseStatus.RUNNING);
        finalizeBenchmark(benchmarkId);
    }

    @Transactional(readOnly = true)
    public BenchmarkResponse getBenchmark(String id) {
        return BenchmarkResponse.from(
                benchmarkRepository.findById(id)
                        .orElseThrow(() -> new RuntimeException("Benchmark not found: " + id))
        );
    }

    @Transactional(readOnly = true)
    public List<BenchmarkResponse> listBenchmarks(User user) {
        return benchmarkRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(BenchmarkResponse::from)
                .toList();
    }

    public byte[] downloadScript(String databaseId) {
        BenchmarkDatabase db = databaseRepository.findById(databaseId).orElseThrow();
        if (db.getScript() == null) throw new RuntimeException("Script not ready");
        return db.getScript().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public byte[] downloadBundle(String benchmarkId) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId)
                .orElseThrow(() -> new RuntimeException("Benchmark not found: " + benchmarkId));
        return bundleService.pack(benchmark);
    }

    @Transactional
    public BenchmarkResponse createFromBundle(byte[] zipBytes, User user) {
        ParsedBundle parsed = bundleService.parse(zipBytes);
        BundleManifest manifest = parsed.manifest();

        Benchmark benchmark = new Benchmark(manifest.topic(), user, manifest.depth());
        if (parsed.logicalSchemaJson() != null) {
            benchmark.setLogicalSchema(parsed.logicalSchemaJson());
        }

        for (BundleManifest.DatabaseEntry entry : manifest.databases()) {
            DatabaseType dbType = DatabaseType.valueOf(entry.dbType().toUpperCase());
            BenchmarkDatabase db = new BenchmarkDatabase(dbType, entry.dbName(), entry.dbVersion());
            String key = BenchmarkBundleService.dbKey(entry.dbName(), entry.dbVersion());
            db.setScript(parsed.scripts().get(key));
            String embeddingMappingsJson = parsed.embeddingMappings().get(key);
            if (embeddingMappingsJson != null) {
                db.setEmbeddingMappings(embeddingMappingsJson);
            }
            if (entry.dockerImage() != null) {
                db.setDockerImage(entry.dockerImage());
            }
            db.setStatus(DatabaseStatus.SCRIPT_READY);
            benchmark.addDatabase(db);
        }
        benchmark.setStatus(BenchmarkStatus.STARTING_CONTAINERS);
        benchmarkRepository.save(benchmark);
        log.info("Imported benchmark {} from bundle with {} databases", benchmark.getId(), manifest.databases().size());

        String benchmarkId = benchmark.getId();
        executor.submit(() -> orchestrateFromBundle(benchmarkId));

        return BenchmarkResponse.from(benchmark);
    }

    public String getScriptPreview(String databaseId) {
        BenchmarkDatabase db = databaseRepository.findById(databaseId).orElseThrow();
        if (db.getScript() == null) return null;
        return db.getScript().substring(0, Math.min(db.getScript().length(), 1000));
    }

    public String getDatabaseLogs(String databaseId, int tailLines) {
        var db = databaseRepository.findById(databaseId).orElseThrow();
        if (db.getContainerId() == null) return "No container running";
        return dockerService.getContainerLogs(db.getContainerId(), tailLines);
    }

    public Optional<String> getContainerId(String databaseId) {
        var db = databaseRepository.findById(databaseId).orElseThrow();
        return Optional.ofNullable(db.getContainerId());
    }

    private void orchestrateBenchmark(String benchmarkId) {
        try {
            generateScripts(benchmarkId);
            startContainers(benchmarkId);
            initializeDatabases(benchmarkId);
            finalizeBenchmark(benchmarkId);
        } catch (ObjectOptimisticLockingFailureException e) {
            log.info("Benchmark {} modified concurrently during orchestration, aborting silently", benchmarkId);
        } catch (Exception e) {
            log.error("Benchmark orchestration failed for {}", benchmarkId, e);
            try {
                failRemainingDatabases(benchmarkId, e.getMessage());
                updateBenchmarkStatus(benchmarkId, BenchmarkStatus.FAILED);
            } catch (ObjectOptimisticLockingFailureException raceWithCancel) {
                log.info("Benchmark {} cancelled while marking as FAILED, skipping", benchmarkId);
            }
        }
    }

    private void orchestrateFromBundle(String benchmarkId) {
        try {
            startContainers(benchmarkId);
            initializeDatabases(benchmarkId);
            finalizeBenchmark(benchmarkId);
        } catch (ObjectOptimisticLockingFailureException e) {
            log.info("Benchmark {} modified concurrently during bundle import, aborting silently", benchmarkId);
        } catch (Exception e) {
            log.error("Bundle import orchestration failed for {}", benchmarkId, e);
            try {
                failRemainingDatabases(benchmarkId, e.getMessage());
                updateBenchmarkStatus(benchmarkId, BenchmarkStatus.FAILED);
            } catch (ObjectOptimisticLockingFailureException raceWithCancel) {
                log.info("Benchmark {} cancelled while marking as FAILED, skipping", benchmarkId);
            }
        }
    }

    private void generateScripts(String benchmarkId) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
        updateBenchmarkStatus(benchmarkId, BenchmarkStatus.GENERATING_SCRIPTS);

        for (BenchmarkDatabase db : benchmark.getDatabases()) {
            updateDatabaseStatus(db.getId(), DatabaseStatus.SCRIPT_GENERATING);
        }
        List<TargetRequest> targets = benchmark.getDatabases().stream()
                .map(db -> new TargetRequest(
                        db.getDbType().name().toLowerCase(),
                        db.getDbName(),
                        db.getDbVersion()
                ))
                .toList();

        ScriptCreatorResponse response = scriptCreatorClient.generate(
                benchmark.getTopic(), benchmark.getDepth(), targets
        );

        long successCount = response.scripts() == null ? 0
                : response.scripts().stream().filter(ScriptResult::success).count();
        if (successCount == 0) {
            throw new RuntimeException("Script generation failed for every database");
        }
        if (!response.success()) {
            log.warn("Partial script-generation success: {}/{} databases produced a script — failed ones will be marked FAILED, the rest will proceed",
                    successCount, response.scripts() == null ? 0 : response.scripts().size());
        }
        benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
        try {
            benchmark.setLogicalSchema(objectMapper.writeValueAsString(response.logicalSchema()));
        } catch (Exception e) {
            log.warn("Failed to serialize logical schema", e);
        }
        benchmarkRepository.save(benchmark);

        for (ScriptResult scriptResult : response.scripts()) {
            if (!scriptResult.success()) continue;
            benchmark.getDatabases().stream()
                    .filter(db -> db.getDbName().equals(scriptResult.dbName())
                            && db.getDbVersion().equals(scriptResult.dbVersion()))
                    .findFirst()
                    .ifPresent(db -> {
                        db.setScript(scriptResult.script());
                        if (scriptResult.container() != null) {
                            db.setDockerImage(scriptResult.container().dockerImage());
                        }
                        if (!scriptResult.embeddingMappings().isEmpty()) {
                            try {
                                db.setEmbeddingMappings(objectMapper.writeValueAsString(scriptResult.embeddingMappings()));
                            } catch (Exception e) {
                                log.warn("Failed to serialize embedding mappings for {}/{}", scriptResult.dbName(), scriptResult.dbVersion(), e);
                            }
                        }
                        db.setStatus(DatabaseStatus.SCRIPT_READY);
                        databaseRepository.save(db);
                        sseEmitterService.broadcastDatabaseStatus(benchmarkId, db.getId(), DatabaseStatus.SCRIPT_READY);
                        sseEmitterService.sendEvent(benchmarkId, SseEvents.EVENT_SCRIPT_GENERATED, Map.of(
                                SseEvents.PAYLOAD_BENCHMARK_ID, benchmarkId,
                                SseEvents.PAYLOAD_DATABASE_ID, db.getId(),
                                SseEvents.PAYLOAD_SCRIPT_PREVIEW, db.getScript().substring(0, Math.min(db.getScript().length(), 500))
                        ));
                    });
        }

        benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
        for (BenchmarkDatabase db : benchmark.getDatabases()) {
            if (db.getStatus() == DatabaseStatus.SCRIPT_GENERATING) {
                db.setStatus(DatabaseStatus.FAILED);
                db.setErrorMessage("Script generation failed");
                databaseRepository.save(db);
                sseEmitterService.broadcastDatabaseStatus(benchmarkId, db.getId(), DatabaseStatus.FAILED, "Script generation failed");
            }
        }
    }

    private void startContainers(String benchmarkId) {
        updateBenchmarkStatus(benchmarkId, BenchmarkStatus.STARTING_CONTAINERS);
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();

        List<BenchmarkDatabase> readyDatabases = benchmark.getDatabases().stream()
                .filter(db -> db.getStatus() == DatabaseStatus.SCRIPT_READY)
                .toList();

        List<? extends Future<?>> futures = readyDatabases.stream()
                .map(db -> executor.submit(() -> startSingleContainer(benchmarkId, db.getId())))
                .toList();

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                log.error("Container start failed", e);
            }
        }
    }

    private void startSingleContainer(String benchmarkId, String dbId) {
        BenchmarkDatabase db = databaseRepository.findById(dbId).orElseThrow();
        try {
            updateDatabaseStatus(db.getId(), DatabaseStatus.CONTAINER_STARTING);

            DatabaseEngine engine = DatabaseEngine.of(db.getDbName());
            int hostPort = dockerService.findAvailablePort();
            String image = db.getDockerImage() != null ? db.getDockerImage() : engine.dockerImage(db.getDbVersion());
            String containerNameRoot = "benchmark-" + benchmarkId.substring(0, 8) + "-" + db.getDbName();
            dockerService.removeContainersByNamePrefix(containerNameRoot);
            String containerName = containerNameRoot + "-" + (System.currentTimeMillis() / 1000L);

            ContainerSpec spec = new ContainerSpec(image, containerName, engine.port(), hostPort, engine.env(), CONTAINER_MEMORY_MB);
            String containerId = dockerService.createAndStartContainer(spec);

            db.setContainerId(containerId);
            db.setHostPort(hostPort);
            databaseRepository.save(db);

            sseEmitterService.broadcastDatabasePortAssigned(benchmarkId, db.getId(), hostPort);
            scriptExecutor.waitForReady(containerId, db.getDbName(), hostPort);
            updateDatabaseStatus(db.getId(), DatabaseStatus.INITIALIZING);
        } catch (Exception e) {
            log.error("Failed to start container for {} ({})", db.getDbName(), dbId, e);
            db.setStatus(DatabaseStatus.FAILED);
            db.setErrorMessage("Container start failed: " + e.getMessage());
            databaseRepository.save(db);
            sseEmitterService.broadcastDatabaseStatus(benchmarkId, db.getId(), DatabaseStatus.FAILED, e.getMessage());
        }
    }

    private void initializeDatabases(String benchmarkId) {
        updateBenchmarkStatus(benchmarkId, BenchmarkStatus.INITIALIZING);
        var benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();

        var initDatabases = benchmark.getDatabases().stream()
                .filter(db -> db.getStatus() == DatabaseStatus.INITIALIZING)
                .toList();

        var futures = initDatabases.stream()
                .map(db -> executor.submit(() -> initializeSingleDatabase(benchmarkId, db.getId())))
                .toList();

        for (var future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                log.error("Database initialization failed", e);
            }
        }
    }

    private void initializeSingleDatabase(String benchmarkId, String dbId) {
        var db = databaseRepository.findById(dbId).orElseThrow();
        try {
            scriptExecutor.executeScript(db.getContainerId(), db.getDbName(), db.getScript(), db.getHostPort());
            updateDatabaseStatus(db.getId(), DatabaseStatus.RUNNING);
            captureBaseline(benchmarkId, dbId);
        } catch (Exception e) {
            log.error("Failed to initialize {} ({})", db.getDbName(), dbId, e);
            db.setStatus(DatabaseStatus.FAILED);
            db.setErrorMessage("Script execution failed: " + e.getMessage());
            databaseRepository.save(db);
            sseEmitterService.broadcastDatabaseStatus(benchmarkId, db.getId(), DatabaseStatus.FAILED, e.getMessage());
        }
    }

    private void captureBaseline(String benchmarkId, String dbId) {
        var db = databaseRepository.findById(dbId).orElseThrow();
        dataSizeProbe.invalidate(dbId);
        Long bytes = dataSizeProbe.sizeOf(db, HOST_ADDRESS);
        if (bytes == null) {
            log.warn("Baseline capture skipped for {} ({}): probe unavailable", db.getDbName(), dbId);
            return;
        }
        db.setBaselineSizeBytes(bytes);
        db.setBaselineRecordedAt(Instant.now());
        databaseRepository.save(db);
        sseEmitterService.sendEvent(benchmarkId, SseEvents.EVENT_DATABASE_SIZE_DIRTY, Map.of("databaseId", dbId));
    }

    private void finalizeBenchmark(String benchmarkId) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
        boolean anyRunning = benchmark.getDatabases().stream()
                .anyMatch(db -> db.getStatus() == DatabaseStatus.RUNNING);
        boolean allFailed = benchmark.getDatabases().stream()
                .allMatch(db -> db.getStatus() == DatabaseStatus.FAILED);
        boolean anyReadyOrStopped = benchmark.getDatabases().stream()
                .anyMatch(db -> db.getStatus() == DatabaseStatus.SCRIPT_READY
                        || db.getStatus() == DatabaseStatus.STOPPED);

        if (allFailed) {
            updateBenchmarkStatus(benchmarkId, BenchmarkStatus.FAILED);
        } else if (anyRunning) {
            updateBenchmarkStatus(benchmarkId, BenchmarkStatus.RUNNING);
        } else if (anyReadyOrStopped) {
            updateBenchmarkStatus(benchmarkId, BenchmarkStatus.READY_TO_RUN);
        } else {
            updateBenchmarkStatus(benchmarkId, BenchmarkStatus.FAILED);
        }
    }

    private void failRemainingDatabases(String benchmarkId, String errorMessage) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
        for (BenchmarkDatabase db : benchmark.getDatabases()) {
            if (db.getStatus() != DatabaseStatus.FAILED && db.getStatus() != DatabaseStatus.RUNNING
                    && db.getStatus() != DatabaseStatus.STOPPED) {
                db.setStatus(DatabaseStatus.FAILED);
                db.setErrorMessage(errorMessage);
                databaseRepository.save(db);
                sseEmitterService.broadcastDatabaseStatus(benchmarkId, db.getId(), DatabaseStatus.FAILED,
                    errorMessage != null ? errorMessage : "Orchestration failed");
            }
        }
    }

    private void cleanupContainer(BenchmarkDatabase db) {
        if (db.getContainerId() != null) {
            try {
                dockerService.removeContainer(db.getContainerId());
            } catch (Exception e) {
                log.warn("Failed to remove old container {}: {}", db.getContainerId(), e.getMessage());
            }
            db.setContainerId(null);
            db.setHostPort(null);
        }
        connectionCacheRegistry.evictAll(db.getId());
        dataSizeProbe.invalidate(db.getId());
    }

    private void updateBenchmarkStatus(String benchmarkId, BenchmarkStatus status) {
        Optional<Benchmark> opt = benchmarkRepository.findById(benchmarkId);
        if (opt.isEmpty()) {
            log.info("Benchmark {} no longer exists, skipping status update to {}", benchmarkId, status);
            return;
        }
        Benchmark benchmark = opt.get();
        benchmark.setStatus(status);
        try {
            benchmarkRepository.save(benchmark);
        } catch (ObjectOptimisticLockingFailureException e) {
            log.info("Benchmark {} modified concurrently, skipping status update to {}", benchmarkId, status);
            return;
        }
        sseEmitterService.broadcastBenchmarkStatus(benchmarkId, status);
    }

    private void updateDatabaseStatus(String databaseId, DatabaseStatus status) {
        Optional<BenchmarkDatabase> opt = databaseRepository.findById(databaseId);
        if (opt.isEmpty()) {
            log.info("Database {} no longer exists, skipping status update to {}", databaseId, status);
            return;
        }
        BenchmarkDatabase db = opt.get();
        db.setStatus(status);
        try {
            databaseRepository.save(db);
        } catch (ObjectOptimisticLockingFailureException e) {
            log.info("Database {} modified concurrently, skipping status update to {}", databaseId, status);
            return;
        }
        String benchmarkId = db.getBenchmark().getId();
        sseEmitterService.broadcastDatabaseStatus(benchmarkId, databaseId, status);
    }
}