package com.dbagnets.backend.benchmark.setup.application;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.dbagnets.backend.benchmark.setup.api.dto.BenchmarkResponse;
import com.dbagnets.backend.benchmark.setup.api.dto.CreateBenchmarkRequest;
import com.dbagnets.backend.benchmark.setup.internal.BenchmarkBundleService;
import com.dbagnets.backend.benchmark.setup.internal.BenchmarkBundleService.ParsedBundle;
import com.dbagnets.backend.benchmark.setup.internal.BundleManifest;
import com.dbagnets.backend.domain.BenchmarkStatus;
import com.dbagnets.backend.domain.DatabaseStatus;
import com.dbagnets.backend.domain.DatabaseType;
import com.dbagnets.backend.engine.registry.EntityIdRegistry;
import com.dbagnets.backend.infrastructure.persistence.BenchmarkDatabaseRepository;
import com.dbagnets.backend.infrastructure.persistence.BenchmarkRepository;
import com.dbagnets.backend.shared.entity.Benchmark;
import com.dbagnets.backend.shared.entity.BenchmarkDatabase;
import com.dbagnets.backend.shared.entity.User;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class BenchmarkLifecycleService {

    private final BenchmarkRepository benchmarkRepository;
    private final BenchmarkDatabaseRepository databaseRepository;
    private final BenchmarkBundleService bundleService;
    private final BenchmarkDeploymentService deployment;
    private final EntityIdRegistry entityIdRegistry;

    @Transactional
    public BenchmarkResponse createBenchmark(CreateBenchmarkRequest request, User user) {
        Benchmark benchmark = new Benchmark(request.topic(), user, request.depth());
        for (CreateBenchmarkRequest.DatabaseTarget target : request.databases()) {
            DatabaseType dbType = DatabaseType.valueOf(target.dbType().toUpperCase());
            BenchmarkDatabase db =
                    new BenchmarkDatabase(dbType, target.dbName(), target.dbVersion());
            benchmark.addDatabase(db);
        }

        benchmarkRepository.save(benchmark);
        log.info(
                "Created benchmark {} with {} databases",
                benchmark.getId(),
                request.databases().size());

        String benchmarkId = benchmark.getId();
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        deployment.deployAsync(benchmarkId);
                    }
                });

        return BenchmarkResponse.from(benchmark);
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
        log.info(
                "Imported benchmark {} from bundle with {} databases",
                benchmark.getId(),
                manifest.databases().size());

        String benchmarkId = benchmark.getId();
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        deployment.deployFromBundleAsync(benchmarkId);
                    }
                });

        return BenchmarkResponse.from(benchmark);
    }

    @Transactional
    public void deleteBenchmark(String benchmarkId) {
        Benchmark benchmark =
                benchmarkRepository
                        .findById(benchmarkId)
                        .orElseThrow(
                                () -> new RuntimeException("Benchmark not found: " + benchmarkId));

        for (BenchmarkDatabase db : benchmark.getDatabases()) {
            deployment.cleanupContainer(db);
        }
        entityIdRegistry.evictAllForBenchmark(benchmarkId);

        benchmarkRepository.delete(benchmark);
        log.info("Deleted benchmark {}", benchmarkId);
    }

    @Transactional
    public void deleteDatabase(String benchmarkId, String databaseId) {
        Benchmark benchmark =
                benchmarkRepository
                        .findById(benchmarkId)
                        .orElseThrow(
                                () -> new RuntimeException("Benchmark not found: " + benchmarkId));
        BenchmarkDatabase db =
                benchmark.getDatabases().stream()
                        .filter(d -> d.getId().equals(databaseId))
                        .findFirst()
                        .orElseThrow(
                                () -> new RuntimeException("Database not found: " + databaseId));

        deployment.cleanupContainer(db);
        entityIdRegistry.evictAllForDatabase(db.getId());
        benchmark.getDatabases().remove(db);

        if (benchmark.getDatabases().isEmpty()) {
            benchmarkRepository.delete(benchmark);
            log.info("Deleted last database {} and benchmark {}", databaseId, benchmarkId);
        } else {
            benchmarkRepository.save(benchmark);
            deployment.finalizeBenchmark(benchmarkId);
            log.info("Deleted database {} from benchmark {}", databaseId, benchmarkId);
        }
    }

    @Transactional(readOnly = true)
    public BenchmarkResponse getBenchmark(String id) {
        return BenchmarkResponse.from(
                benchmarkRepository
                        .findById(id)
                        .orElseThrow(() -> new RuntimeException("Benchmark not found: " + id)));
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
        Benchmark benchmark =
                benchmarkRepository
                        .findById(benchmarkId)
                        .orElseThrow(
                                () -> new RuntimeException("Benchmark not found: " + benchmarkId));
        return bundleService.pack(benchmark);
    }

    public String getScriptPreview(String databaseId) {
        BenchmarkDatabase db = databaseRepository.findById(databaseId).orElseThrow();
        if (db.getScript() == null) return null;
        return db.getScript().substring(0, Math.min(db.getScript().length(), 1000));
    }

    public Optional<String> getContainerId(String databaseId) {
        var db = databaseRepository.findById(databaseId).orElseThrow();
        return Optional.ofNullable(db.getContainerId());
    }
}
