package com.dbagnets.backend.service;

import com.dbagnets.backend.client.ScriptCreatorClient;
import com.dbagnets.backend.client.ScriptCreatorRequest;
import com.dbagnets.backend.client.ScriptCreatorResponse;
import com.dbagnets.backend.docker.ContainerSpec;
import com.dbagnets.backend.docker.DockerService;
import com.dbagnets.backend.docker.ScriptExecutor;
import com.dbagnets.backend.entity.*;
import com.dbagnets.backend.model.BenchmarkResponse;
import com.dbagnets.backend.model.CreateBenchmarkRequest;
import com.dbagnets.backend.repository.BenchmarkDatabaseRepository;
import com.dbagnets.backend.repository.BenchmarkRepository;
import com.dbagnets.backend.sse.SseEmitterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class BenchmarkService {
    private static final Logger log = LoggerFactory.getLogger(BenchmarkService.class);

    private final BenchmarkRepository benchmarkRepository;
    private final BenchmarkDatabaseRepository databaseRepository;
    private final ScriptCreatorClient scriptCreatorClient;
    private final DockerService dockerService;
    private final ScriptExecutor scriptExecutor;
    private final SseEmitterService sseEmitterService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public BenchmarkService(
        BenchmarkRepository benchmarkRepository,
        BenchmarkDatabaseRepository databaseRepository,
        ScriptCreatorClient scriptCreatorClient,
        DockerService dockerService,
        ScriptExecutor scriptExecutor,
        SseEmitterService sseEmitterService,
        ObjectMapper objectMapper
    ) {
        this.benchmarkRepository = benchmarkRepository;
        this.databaseRepository = databaseRepository;
        this.scriptCreatorClient = scriptCreatorClient;
        this.dockerService = dockerService;
        this.scriptExecutor = scriptExecutor;
        this.sseEmitterService = sseEmitterService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public BenchmarkResponse createBenchmark(CreateBenchmarkRequest request, String userEmail) {
        var benchmark = new Benchmark(request.topic(), userEmail);

        for (var target : request.databases()) {
            var dbType = DatabaseType.valueOf(target.dbType().toUpperCase());
            var db = new BenchmarkDatabase(dbType, target.dbName(), target.dbVersion());
            benchmark.addDatabase(db);
        }

        benchmarkRepository.save(benchmark);
        log.info("Created benchmark {} with {} databases", benchmark.getId(), request.databases().size());

        executor.submit(() -> orchestrateBenchmark(benchmark.getId()));

        return BenchmarkResponse.from(benchmark);
    }

    private void orchestrateBenchmark(String benchmarkId) {
        try {
            generateScripts(benchmarkId);
            startContainers(benchmarkId);
            initializeDatabases(benchmarkId);
            finalizeBenchmark(benchmarkId);
        } catch (Exception e) {
            log.error("Benchmark orchestration failed for {}", benchmarkId, e);
            failRemainingDatabases(benchmarkId, e.getMessage());
            updateBenchmarkStatus(benchmarkId, BenchmarkStatus.FAILED);
        }
    }

    private void generateScripts(String benchmarkId) {
        var benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
        updateBenchmarkStatus(benchmarkId, BenchmarkStatus.GENERATING_SCRIPTS);

        for (var db : benchmark.getDatabases()) {
            updateDatabaseStatus(db.getId(), DatabaseStatus.SCRIPT_GENERATING);
        }

        var targets = benchmark.getDatabases().stream()
            .map(db -> new ScriptCreatorRequest.TargetRequest(
                db.getDbType().name().toLowerCase(),
                db.getDbName(),
                db.getDbVersion()
            ))
            .toList();

        ScriptCreatorResponse response = scriptCreatorClient.generate(
            benchmark.getTopic(), 4, targets
        );

        if (!response.success()) {
            throw new RuntimeException("Script generation failed");
        }

        // Save logical schema
        benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
        try {
            benchmark.setLogicalSchema(objectMapper.writeValueAsString(response.logicalSchema()));
        } catch (Exception e) {
            log.warn("Failed to serialize logical schema", e);
        }
        benchmarkRepository.save(benchmark);

        // Match scripts to databases and save
        for (var scriptResult : response.scripts()) {
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
                    db.setStatus(DatabaseStatus.SCRIPT_READY);
                    databaseRepository.save(db);
                    sseEmitterService.sendEvent(benchmarkId, "database_status", Map.of(
                        "benchmarkId", benchmarkId,
                        "databaseId", db.getId(),
                        "status", "SCRIPT_READY"
                    ));
                    sseEmitterService.sendEvent(benchmarkId, "script_generated", Map.of(
                        "benchmarkId", benchmarkId,
                        "databaseId", db.getId(),
                        "scriptPreview", db.getScript().substring(0, Math.min(db.getScript().length(), 500))
                    ));
                });
        }

        // Mark failed scripts
        benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
        for (var db : benchmark.getDatabases()) {
            if (db.getStatus() == DatabaseStatus.SCRIPT_GENERATING) {
                db.setStatus(DatabaseStatus.FAILED);
                db.setErrorMessage("Script generation failed");
                databaseRepository.save(db);
                sseEmitterService.sendEvent(benchmarkId, "database_status", Map.of(
                    "benchmarkId", benchmarkId,
                    "databaseId", db.getId(),
                    "status", "FAILED",
                    "errorMessage", "Script generation failed"
                ));
            }
        }
    }

    private void startContainers(String benchmarkId) {
        updateBenchmarkStatus(benchmarkId, BenchmarkStatus.STARTING_CONTAINERS);
        var benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();

        var readyDatabases = benchmark.getDatabases().stream()
            .filter(db -> db.getStatus() == DatabaseStatus.SCRIPT_READY)
            .toList();

        var futures = readyDatabases.stream()
            .map(db -> executor.submit(() -> startSingleContainer(benchmarkId, db.getId())))
            .toList();

        for (var future : futures) {
            try { future.get(); } catch (Exception e) {
                log.error("Container start failed", e);
            }
        }
    }

    private void startSingleContainer(String benchmarkId, String dbId) {
        var db = databaseRepository.findById(dbId).orElseThrow();
        try {
            updateDatabaseStatus(db.getId(), DatabaseStatus.CONTAINER_STARTING);

            int hostPort = dockerService.findAvailablePort();
            String image = db.getDockerImage() != null ? db.getDockerImage() : getDefaultDockerImage(db.getDbName(), db.getDbVersion());
            String containerName = "benchmark-" + benchmarkId.substring(0, 8) + "-" + db.getDbName();

            int containerPort = getDefaultPort(db.getDbName());
            Map<String, String> env = getDefaultEnvironment(db.getDbName());

            long memoryMb = getMemoryMb(db.getDbName());
            var spec = new ContainerSpec(image, containerName, containerPort, hostPort, env, memoryMb);
            String containerId = dockerService.createAndStartContainer(spec);

            db.setContainerId(containerId);
            db.setHostPort(hostPort);
            databaseRepository.save(db);

            sseEmitterService.sendEvent(benchmarkId, "database_port_assigned", Map.of(
                "benchmarkId", benchmarkId,
                "databaseId", db.getId(),
                "hostPort", hostPort
            ));

            // Wait for container to be ready
            scriptExecutor.waitForReady(containerId, db.getDbName(), hostPort);
            updateDatabaseStatus(db.getId(), DatabaseStatus.INITIALIZING);
        } catch (Exception e) {
            log.error("Failed to start container for {} ({})", db.getDbName(), dbId, e);
            db.setStatus(DatabaseStatus.FAILED);
            db.setErrorMessage("Container start failed: " + e.getMessage());
            databaseRepository.save(db);
            sseEmitterService.sendEvent(benchmarkId, "database_status", Map.of(
                "benchmarkId", benchmarkId,
                "databaseId", db.getId(),
                "status", "FAILED",
                "errorMessage", e.getMessage()
            ));
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
            try { future.get(); } catch (Exception e) {
                log.error("Database initialization failed", e);
            }
        }
    }

    private void initializeSingleDatabase(String benchmarkId, String dbId) {
        var db = databaseRepository.findById(dbId).orElseThrow();
        try {
            scriptExecutor.executeScript(db.getContainerId(), db.getDbName(), db.getScript(), db.getHostPort());
            updateDatabaseStatus(db.getId(), DatabaseStatus.RUNNING);
        } catch (Exception e) {
            log.error("Failed to initialize {} ({})", db.getDbName(), dbId, e);
            db.setStatus(DatabaseStatus.FAILED);
            db.setErrorMessage("Script execution failed: " + e.getMessage());
            databaseRepository.save(db);
            sseEmitterService.sendEvent(benchmarkId, "database_status", Map.of(
                "benchmarkId", benchmarkId,
                "databaseId", db.getId(),
                "status", "FAILED",
                "errorMessage", e.getMessage()
            ));
        }
    }

    private void finalizeBenchmark(String benchmarkId) {
        var benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
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
        var benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
        for (var db : benchmark.getDatabases()) {
            if (db.getStatus() != DatabaseStatus.FAILED && db.getStatus() != DatabaseStatus.RUNNING
                    && db.getStatus() != DatabaseStatus.STOPPED) {
                db.setStatus(DatabaseStatus.FAILED);
                db.setErrorMessage(errorMessage);
                databaseRepository.save(db);
                sseEmitterService.sendEvent(benchmarkId, "database_status", Map.of(
                    "benchmarkId", benchmarkId,
                    "databaseId", db.getId(),
                    "status", "FAILED",
                    "errorMessage", errorMessage != null ? errorMessage : "Orchestration failed"
                ));
            }
        }
    }

    public void redeployBenchmark(String benchmarkId) {
        var benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
        var failedDbs = benchmark.getDatabases().stream()
            .filter(db -> db.getStatus() == DatabaseStatus.FAILED && db.getScript() != null)
            .toList();

        if (failedDbs.isEmpty()) {
            throw new RuntimeException("No failed databases with scripts to redeploy");
        }

        log.info("Redeploying benchmark {} with {} failed databases", benchmarkId, failedDbs.size());

        for (var db : failedDbs) {
            cleanupContainer(db);
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
            } catch (Exception e) {
                log.error("Redeploy failed for benchmark {}", benchmarkId, e);
                failRemainingDatabases(benchmarkId, e.getMessage());
                updateBenchmarkStatus(benchmarkId, BenchmarkStatus.FAILED);
            }
        });
    }

    public void redeployDatabase(String benchmarkId, String databaseId) {
        var db = databaseRepository.findById(databaseId).orElseThrow();

        if (db.getScript() == null) {
            throw new RuntimeException("No script available for redeploy");
        }

        log.info("Redeploying database {} ({}) in benchmark {}", db.getDbName(), databaseId, benchmarkId);

        cleanupContainer(db);
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
            } catch (Exception e) {
                log.error("Redeploy failed for database {} in benchmark {}", databaseId, benchmarkId, e);
                var failedDb = databaseRepository.findById(databaseId).orElseThrow();
                failedDb.setStatus(DatabaseStatus.FAILED);
                failedDb.setErrorMessage(e.getMessage());
                databaseRepository.save(failedDb);
                finalizeBenchmark(benchmarkId);
            }
        });
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
    }

    @Transactional
    public void deleteBenchmark(String benchmarkId) {
        var benchmark = benchmarkRepository.findById(benchmarkId)
            .orElseThrow(() -> new RuntimeException("Benchmark not found: " + benchmarkId));

        for (var db : benchmark.getDatabases()) {
            cleanupContainer(db);
        }

        benchmarkRepository.delete(benchmark);
        log.info("Deleted benchmark {}", benchmarkId);
    }

    @Transactional(readOnly = true)
    public BenchmarkResponse getBenchmark(String id) {
        return BenchmarkResponse.from(
            benchmarkRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Benchmark not found: " + id))
        );
    }

    @Transactional(readOnly = true)
    public List<BenchmarkResponse> listBenchmarks(String userEmail) {
        return benchmarkRepository.findByUserEmailOrderByCreatedAtDesc(userEmail).stream()
            .map(BenchmarkResponse::from)
            .toList();
    }

    public byte[] downloadScript(String benchmarkId, String databaseId) {
        var db = databaseRepository.findById(databaseId).orElseThrow();
        if (db.getScript() == null) throw new RuntimeException("Script not ready");
        return db.getScript().getBytes(StandardCharsets.UTF_8);
    }

    public String getScriptPreview(String benchmarkId, String databaseId) {
        var db = databaseRepository.findById(databaseId).orElseThrow();
        if (db.getScript() == null) return null;
        return db.getScript().substring(0, Math.min(db.getScript().length(), 1000));
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

    public String getDatabaseLogs(String benchmarkId, String databaseId, int tailLines) {
        var db = databaseRepository.findById(databaseId).orElseThrow();
        if (db.getContainerId() == null) return "No container running";
        return dockerService.getContainerLogs(db.getContainerId(), tailLines);
    }

    public String getContainerId(String databaseId) {
        var db = databaseRepository.findById(databaseId).orElseThrow();
        return db.getContainerId();
    }

    private void updateBenchmarkStatus(String benchmarkId, BenchmarkStatus status) {
        var benchmark = benchmarkRepository.findById(benchmarkId).orElseThrow();
        benchmark.setStatus(status);
        benchmarkRepository.save(benchmark);
        sseEmitterService.sendEvent(benchmarkId, "benchmark_status", Map.of(
            "benchmarkId", benchmarkId,
            "status", status.name()
        ));
    }

    private void updateDatabaseStatus(String databaseId, DatabaseStatus status) {
        var db = databaseRepository.findById(databaseId).orElseThrow();
        db.setStatus(status);
        databaseRepository.save(db);
        String benchmarkId = db.getBenchmark().getId();
        sseEmitterService.sendEvent(benchmarkId, "database_status", Map.of(
            "benchmarkId", benchmarkId,
            "databaseId", databaseId,
            "status", status.name()
        ));
    }

    private int getDefaultPort(String dbName) {
        return switch (dbName.toLowerCase()) {
            case "postgresql", "timescaledb" -> 5432;
            case "mysql" -> 3306;
            case "neo4j", "memgraph" -> 7687;
            case "mongodb" -> 27017;
            case "redis" -> 6379;
            case "arangodb" -> 8529;
            case "elasticsearch" -> 9200;
            case "couchdb" -> 5984;
            case "milvus" -> 19530;
            case "qdrant" -> 6333;
            case "weaviate" -> 8080;
            case "influxdb" -> 8086;
            case "questdb" -> 9000;
            case "dynamodb" -> 8000;
            case "etcd" -> 2379;
            default -> 8080;
        };
    }

    private String getDefaultDockerImage(String dbName, String dbVersion) {
        return switch (dbName.toLowerCase()) {
            case "postgresql" -> "postgres:" + dbVersion;
            case "mysql" -> "mysql:" + dbVersion;
            case "neo4j" -> "neo4j:" + dbVersion;
            case "arangodb" -> "arangodb:" + dbVersion;
            case "memgraph" -> "memgraph/memgraph:" + dbVersion;
            case "milvus" -> "milvusdb/milvus:v" + dbVersion + "-latest";
            case "qdrant" -> "qdrant/qdrant:v" + dbVersion;
            case "weaviate" -> "semitechnologies/weaviate:" + dbVersion;
            case "mongodb" -> "mongo:" + dbVersion;
            case "couchdb" -> "couchdb:" + dbVersion;
            case "elasticsearch" -> "docker.elastic.co/elasticsearch/elasticsearch:" + dbVersion + ".0";
            case "redis" -> "redis:" + dbVersion;
            case "dynamodb" -> "amazon/dynamodb-local:latest";
            case "etcd" -> "bitnami/etcd:" + dbVersion;
            case "timescaledb" -> "timescale/timescaledb:latest-pg17";
            case "influxdb" -> "influxdb:" + dbVersion;
            case "questdb" -> "questdb/questdb:" + dbVersion;
            default -> dbName + ":" + dbVersion;
        };
    }

    private long getMemoryMb(String dbName) {
        return switch (dbName.toLowerCase()) {
            case "neo4j" -> 1024;
            case "elasticsearch" -> 768;
            default -> 512;
        };
    }

    private Map<String, String> getDefaultEnvironment(String dbName) {
        return switch (dbName.toLowerCase()) {
            case "postgresql", "timescaledb" -> Map.of("POSTGRES_PASSWORD", "benchmark", "POSTGRES_DB", "benchmark");
            case "mysql" -> Map.of("MYSQL_ROOT_PASSWORD", "root", "MYSQL_DATABASE", "benchmark");
            case "neo4j" -> Map.of(
                "NEO4J_AUTH", "neo4j/benchmark",
                "NEO4J_server_memory_heap_initial__size", "256m",
                "NEO4J_server_memory_heap_max__size", "512m",
                "NEO4J_server_memory_pagecache_size", "128m"
            );
            case "memgraph" -> Map.of("MEMGRAPH_USER", "memgraph", "MEMGRAPH_PASSWORD", "memgraph");
            case "mongodb" -> Map.of();
            case "redis" -> Map.of();
            case "arangodb" -> Map.of("ARANGO_ROOT_PASSWORD", "root");
            case "elasticsearch" -> Map.of("discovery.type", "single-node", "xpack.security.enabled", "false", "ES_JAVA_OPTS", "-Xms256m -Xmx256m");
            case "couchdb" -> Map.of("COUCHDB_USER", "admin", "COUCHDB_PASSWORD", "benchmark");
            case "milvus" -> Map.of();
            case "qdrant" -> Map.of();
            case "weaviate" -> Map.of("AUTHENTICATION_ANONYMOUS_ACCESS_ENABLED", "true", "PERSISTENCE_DATA_PATH", "/var/lib/weaviate");
            case "influxdb" -> Map.of("DOCKER_INFLUXDB_INIT_MODE", "setup", "DOCKER_INFLUXDB_INIT_USERNAME", "admin", "DOCKER_INFLUXDB_INIT_PASSWORD", "benchmark", "DOCKER_INFLUXDB_INIT_ORG", "benchmark", "DOCKER_INFLUXDB_INIT_BUCKET", "benchmark");
            case "questdb" -> Map.of("QDB_CAIRO_COMMIT_LAG", "1000");
            case "dynamodb" -> Map.of();
            case "etcd" -> Map.of("ETCD_ADVERTISE_CLIENT_URLS", "http://0.0.0.0:2379", "ETCD_LISTEN_CLIENT_URLS", "http://0.0.0.0:2379");
            default -> Map.of();
        };
    }
}
