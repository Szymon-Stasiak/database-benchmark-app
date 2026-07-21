package com.dbagnets.backend.benchmark.setup.application;

import com.dbagnets.backend.benchmark.result.application.DataSizeProbe;
import com.dbagnets.backend.benchmark.setup.port.ContainerManagementPort;
import com.dbagnets.backend.benchmark.setup.port.ScriptExecutionPort;
import com.dbagnets.backend.benchmark.setup.port.ScriptGenerationPort;
import com.dbagnets.backend.domain.BenchmarkStatus;
import com.dbagnets.backend.domain.DatabaseEngine;
import com.dbagnets.backend.domain.DatabaseStatus;
import com.dbagnets.backend.engine.driver.ConnectionCacheRegistry;
import com.dbagnets.backend.infrastructure.docker.ContainerSpec;
import com.dbagnets.backend.infrastructure.persistence.BenchmarkDatabaseRepository;
import com.dbagnets.backend.infrastructure.persistence.BenchmarkRepository;
import com.dbagnets.backend.infrastructure.scriptgen.ScriptCreatorRequest.TargetRequest;
import com.dbagnets.backend.infrastructure.scriptgen.ScriptCreatorResponse;
import com.dbagnets.backend.infrastructure.scriptgen.ScriptCreatorResponse.ScriptResult;
import com.dbagnets.backend.infrastructure.sse.SseEvents;
import com.dbagnets.backend.shared.entity.Benchmark;
import com.dbagnets.backend.shared.entity.BenchmarkDatabase;
import com.dbagnets.backend.shared.event.BenchmarkEventPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

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
public class BenchmarkDeploymentService {

    private static final long CONTAINER_MEMORY_MB = 2048;

    @Value("${app.container-host}")
    private String hostAddress;

    private final BenchmarkRepository benchmarkRepository;
    private final BenchmarkDatabaseRepository databaseRepository;
    private final ScriptGenerationPort scriptGenerator;
    private final ContainerManagementPort containerManager;
    private final ScriptExecutionPort scriptExecutor;
    private final BenchmarkEventPort events;
    private final DataSizeProbe dataSizeProbe;
    private final ConnectionCacheRegistry connectionCacheRegistry;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public void deployAsync(String benchmarkId) {
        executor.submit(() -> {
            try {
                generateScripts(benchmarkId);
                startContainers(benchmarkId);
                initializeDatabases(benchmarkId);
                finalizeBenchmark(benchmarkId);
            } catch (ObjectOptimisticLockingFailureException e) {
                log.info("Benchmark {} modified concurrently during orchestration, aborting silently", benchmarkId);
            } catch (Exception e) {
                log.error("Benchmark orchestration failed for {}", benchmarkId, e);
                handleOrchestrationFailure(benchmarkId, e);
            }
        });
    }

    public void deployFromBundleAsync(String benchmarkId) {
        executor.submit(() -> {
            try {
                startContainers(benchmarkId);
                initializeDatabases(benchmarkId);
                finalizeBenchmark(benchmarkId);
            } catch (ObjectOptimisticLockingFailureException e) {
                log.info("Benchmark {} modified concurrently during bundle import, aborting silently", benchmarkId);
            } catch (Exception e) {
                log.error("Bundle import orchestration failed for {}", benchmarkId, e);
                handleOrchestrationFailure(benchmarkId, e);
            }
        });
    }

    public void redeployAsync(String benchmarkId) {
        executor.submit(() -> {
            try {
                startContainers(benchmarkId);
                initializeDatabases(benchmarkId);
                finalizeBenchmark(benchmarkId);
            } catch (ObjectOptimisticLockingFailureException e) {
                log.info("Benchmark {} modified concurrently during redeploy, aborting silently", benchmarkId);
            } catch (Exception e) {
                log.error("Redeploy failed for benchmark {}", benchmarkId, e);
                handleOrchestrationFailure(benchmarkId, e);
            }
        });
    }

    public void redeploySingleDatabaseAsync(String benchmarkId, String databaseId) {
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
                markDatabaseFailed(benchmarkId, databaseId, e.getMessage());
            }
        });
    }

    public void updateBenchmarkStatus(String benchmarkId, BenchmarkStatus status) {
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
        events.broadcastBenchmarkStatus(benchmarkId, status);
    }

    public void updateDatabaseStatus(String databaseId, DatabaseStatus status) {
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
        events.broadcastDatabaseStatus(benchmarkId, databaseId, status);
    }

    public void finalizeBenchmark(String benchmarkId) {
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

    public void cleanupContainer(BenchmarkDatabase db) {
        if (db.getContainerId() != null) {
            try {
                containerManager.removeContainer(db.getContainerId());
            } catch (Exception e) {
                log.warn("Failed to remove old container {}: {}", db.getContainerId(), e.getMessage());
            }
            db.setContainerId(null);
            db.setHostPort(null);
        }
        connectionCacheRegistry.evictAll(db.getId());
        dataSizeProbe.invalidate(db.getId());
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

        ScriptCreatorResponse response = scriptGenerator.generate(
                benchmark.getTopic(), benchmark.getDepth(), targets
        );

        long successCount = response.scripts().stream().filter(ScriptResult::success).count();
        if (successCount == 0) {
            throw new RuntimeException("Script generation failed for every database");
        }
        if (!response.success()) {
            log.warn("Partial script-generation success: {}/{} databases produced a script — failed ones will be marked FAILED, the rest will proceed",
                    successCount, response.scripts().size());
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
                    .ifPresent(db -> applyGeneratedScript(benchmarkId, db, scriptResult));
        }

        benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
        for (BenchmarkDatabase db : benchmark.getDatabases()) {
            if (db.getStatus() == DatabaseStatus.SCRIPT_GENERATING) {
                db.setStatus(DatabaseStatus.FAILED);
                db.setErrorMessage("Script generation failed");
                databaseRepository.save(db);
                events.broadcastDatabaseStatus(benchmarkId, db.getId(), DatabaseStatus.FAILED, "Script generation failed");
            }
        }
    }

    private void applyGeneratedScript(String benchmarkId, BenchmarkDatabase db, ScriptResult scriptResult) {
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
        events.broadcastDatabaseStatus(benchmarkId, db.getId(), DatabaseStatus.SCRIPT_READY);
        events.sendEvent(benchmarkId, SseEvents.EVENT_SCRIPT_GENERATED, Map.of(
                SseEvents.PAYLOAD_BENCHMARK_ID, benchmarkId,
                SseEvents.PAYLOAD_DATABASE_ID, db.getId(),
                SseEvents.PAYLOAD_SCRIPT_PREVIEW, db.getScript().substring(0, Math.min(db.getScript().length(), 500))
        ));
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
            int hostPort = containerManager.findAvailablePort();
            String image = db.getDockerImage() != null ? db.getDockerImage() : engine.dockerImage(db.getDbVersion());
            String containerNameRoot = "benchmark-" + benchmarkId.substring(0, 8) + "-" + db.getDbName();
            containerManager.removeContainersByNamePrefix(containerNameRoot);
            String containerName = containerNameRoot + "-" + (System.currentTimeMillis() / 1000L);

            ContainerSpec spec = new ContainerSpec(image, containerName, engine.port(), hostPort, engine.env(), CONTAINER_MEMORY_MB);
            String containerId = containerManager.createAndStartContainer(spec);

            db.setContainerId(containerId);
            db.setHostPort(hostPort);
            databaseRepository.save(db);

            events.broadcastDatabasePortAssigned(benchmarkId, db.getId(), hostPort);
            scriptExecutor.waitForReady(containerId, db.getDbName(), hostPort);
            updateDatabaseStatus(db.getId(), DatabaseStatus.INITIALIZING);
        } catch (Exception e) {
            log.error("Failed to start container for {} ({})", db.getDbName(), dbId, e);
            db.setStatus(DatabaseStatus.FAILED);
            db.setErrorMessage("Container start failed: " + e.getMessage());
            databaseRepository.save(db);
            events.broadcastDatabaseStatus(benchmarkId, db.getId(), DatabaseStatus.FAILED, e.getMessage());
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
            events.broadcastDatabaseStatus(benchmarkId, db.getId(), DatabaseStatus.FAILED, e.getMessage());
        }
    }

    private void captureBaseline(String benchmarkId, String dbId) {
        var db = databaseRepository.findById(dbId).orElseThrow();
        dataSizeProbe.invalidate(dbId);
        Long bytes = dataSizeProbe.sizeOf(db, hostAddress);
        if (bytes == null) {
            log.warn("Baseline capture skipped for {} ({}): probe unavailable", db.getDbName(), dbId);
            return;
        }
        db.setBaselineSizeBytes(bytes);
        db.setBaselineRecordedAt(Instant.now());
        databaseRepository.save(db);
        events.sendEvent(benchmarkId, SseEvents.EVENT_DATABASE_SIZE_DIRTY, Map.of("databaseId", dbId));
    }

    private void handleOrchestrationFailure(String benchmarkId, Exception e) {
        try {
            failRemainingDatabases(benchmarkId, e.getMessage());
            updateBenchmarkStatus(benchmarkId, BenchmarkStatus.FAILED);
        } catch (ObjectOptimisticLockingFailureException raceWithCancel) {
            log.info("Benchmark {} cancelled while marking as FAILED, skipping", benchmarkId);
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
                events.broadcastDatabaseStatus(benchmarkId, db.getId(), DatabaseStatus.FAILED,
                    errorMessage != null ? errorMessage : "Orchestration failed");
            }
        }
    }

    private void markDatabaseFailed(String benchmarkId, String databaseId, String message) {
        try {
            Optional<BenchmarkDatabase> failedOpt = databaseRepository.findById(databaseId);
            if (failedOpt.isPresent()) {
                BenchmarkDatabase failedDb = failedOpt.get();
                failedDb.setStatus(DatabaseStatus.FAILED);
                failedDb.setErrorMessage(message);
                databaseRepository.save(failedDb);
            }
            finalizeBenchmark(benchmarkId);
        } catch (ObjectOptimisticLockingFailureException raceWithCancel) {
            log.info("Database {} cancelled while marking as FAILED, skipping", databaseId);
        }
    }
}
