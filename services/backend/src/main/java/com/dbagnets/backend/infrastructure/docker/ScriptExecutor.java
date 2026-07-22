package com.dbagnets.backend.infrastructure.docker;

import com.dbagnets.backend.infrastructure.docker.strategy.*;
import com.dbagnets.backend.benchmark.setup.port.ScriptExecutionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScriptExecutor implements ScriptExecutionPort {
    private final DockerService dockerService;

    public void waitForReady(String containerId, String dbName, int hostPort) {
        log.info("Waiting for {} to be ready...", dbName);
        getStrategy(dbName).waitForReady(dockerService, containerId, hostPort);
    }

    public void executeScript(String containerId, String dbName, String script, int hostPort) {
        log.info("Executing init script for {}...", dbName);
        getStrategy(dbName).execute(dockerService, containerId, script, hostPort);
    }

    private ScriptExecutionStrategy getStrategy(String dbName) {
        return switch (dbName.toLowerCase()) {
            case "postgresql", "timescaledb" -> new PostgresScriptStrategy();
            case "mysql" -> new MysqlScriptStrategy();
            case "neo4j", "memgraph" -> new CypherScriptStrategy(dbName);
            case "mongodb" -> new MongoScriptStrategy();
            case "redis" -> new RedisScriptStrategy();
            case "arangodb" -> new ArangoScriptStrategy();
            case "etcd" -> new EtcdScriptStrategy();
            case "questdb" -> new QuestdbScriptStrategy();
            case "elasticsearch", "couchdb", "qdrant", "weaviate",
                 "influxdb", "dynamodb" -> new HttpApiScriptStrategy(dbName);
            default -> throw new IllegalArgumentException("Unsupported database: " + dbName);
        };
    }
}