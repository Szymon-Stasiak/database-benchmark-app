package com.dbagnets.backend.benchmark.setup.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dbagnets.backend.benchmark.result.application.DataSizeProbe;
import com.dbagnets.backend.benchmark.setup.port.ContainerManagementPort;
import com.dbagnets.backend.domain.BenchmarkStatus;
import com.dbagnets.backend.domain.DatabaseStatus;
import com.dbagnets.backend.engine.driver.support.ConnectionCacheRegistry;
import com.dbagnets.backend.engine.registry.EntityIdRegistry;
import com.dbagnets.backend.infrastructure.persistence.BenchmarkDatabaseRepository;
import com.dbagnets.backend.infrastructure.persistence.BenchmarkRepository;
import com.dbagnets.backend.shared.entity.Benchmark;
import com.dbagnets.backend.shared.entity.BenchmarkDatabase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class BenchmarkOperationsService {

    private final BenchmarkRepository benchmarkRepository;
    private final BenchmarkDatabaseRepository databaseRepository;
    private final ContainerManagementPort containerManager;
    private final BenchmarkDeploymentService deployment;
    private final DataSizeProbe dataSizeProbe;
    private final EntityIdRegistry entityIdRegistry;
    private final ConnectionCacheRegistry connectionCacheRegistry;

    public void redeployBenchmark(String benchmarkId) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
        List<BenchmarkDatabase> redeployableDbs =
                benchmark.getDatabases().stream()
                        .filter(db -> db.getScript() != null && db.getStatus().isRedeployable())
                        .toList();

        if (redeployableDbs.isEmpty()) {
            throw new RuntimeException("No databases available for redeployment");
        }

        log.info("Redeploying benchmark {} with {} databases", benchmarkId, redeployableDbs.size());

        for (BenchmarkDatabase db : redeployableDbs) {
            deployment.cleanupContainer(db);
            entityIdRegistry.evictAllForDatabase(db.getId());
            db.setStatus(DatabaseStatus.SCRIPT_READY);
            db.setErrorMessage(null);
            databaseRepository.save(db);
        }

        deployment.updateBenchmarkStatus(benchmarkId, BenchmarkStatus.STARTING_CONTAINERS);
        deployment.redeployAsync(benchmarkId);
    }

    public void redeployDatabase(String benchmarkId, String databaseId) {
        BenchmarkDatabase db = databaseRepository.findById(databaseId).orElseThrow();

        if (db.getScript() == null) {
            throw new RuntimeException("No script available for redeploy");
        }

        log.info(
                "Redeploying database {} ({}) in benchmark {}",
                db.getDbName(),
                databaseId,
                benchmarkId);

        deployment.cleanupContainer(db);
        entityIdRegistry.evictAllForDatabase(db.getId());
        db.setStatus(DatabaseStatus.SCRIPT_READY);
        db.setErrorMessage(null);
        databaseRepository.save(db);
        deployment.updateDatabaseStatus(databaseId, DatabaseStatus.SCRIPT_READY);
        deployment.updateBenchmarkStatus(benchmarkId, BenchmarkStatus.STARTING_CONTAINERS);
        deployment.redeploySingleDatabaseAsync(benchmarkId, databaseId);
    }

    @Transactional
    public void hardResetBenchmark(String benchmarkId) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
        log.info(
                "HARD RESET requested for benchmark {} — wiping {} containers and volumes",
                benchmarkId,
                benchmark.getDatabases().size());

        String benchmarkPrefix = "benchmark-" + benchmarkId.substring(0, 8) + "-";
        for (BenchmarkDatabase db : benchmark.getDatabases()) {
            if (db.getContainerId() != null) {
                try {
                    containerManager.stopContainer(db.getContainerId());
                } catch (Exception ignored) {
                }
                containerManager.hardRemoveContainer(db.getContainerId());
                db.setContainerId(null);
                db.setHostPort(null);
            }
            containerManager.removeContainersByNamePrefix(benchmarkPrefix + db.getDbName());
            connectionCacheRegistry.evictAll(db.getId());
            dataSizeProbe.invalidate(db.getId());
            entityIdRegistry.evictAllForDatabase(db.getId());
            db.setStatus(DatabaseStatus.SCRIPT_READY);
            db.setErrorMessage(null);
            db.setBaselineSizeBytes(null);
            db.setBaselineRecordedAt(null);
            databaseRepository.save(db);
        }
        deployment.updateBenchmarkStatus(benchmarkId, BenchmarkStatus.STARTING_CONTAINERS);
        deployment.redeployAsync(benchmarkId);
    }

    @Transactional
    public void stopDatabase(String benchmarkId, String databaseId) {
        var db = databaseRepository.findById(databaseId).orElseThrow();
        if (db.getStatus() == DatabaseStatus.STOPPED) {
            return;
        }
        if (db.getContainerId() != null) {
            containerManager.stopContainer(db.getContainerId());
        }
        deployment.updateDatabaseStatus(databaseId, DatabaseStatus.STOPPED);
        deployment.finalizeBenchmark(benchmarkId);
    }

    @Transactional
    public void restartDatabase(String benchmarkId, String databaseId) {
        var db = databaseRepository.findById(databaseId).orElseThrow();
        if (db.getContainerId() != null) {
            containerManager.restartContainer(db.getContainerId());
        }
        deployment.updateDatabaseStatus(databaseId, DatabaseStatus.RUNNING);
        deployment.finalizeBenchmark(benchmarkId);
    }

    public String getDatabaseLogs(String databaseId, int tailLines) {
        var db = databaseRepository.findById(databaseId).orElseThrow();
        if (db.getContainerId() == null) return "No container running";
        return containerManager.getContainerLogs(db.getContainerId(), tailLines);
    }
}
