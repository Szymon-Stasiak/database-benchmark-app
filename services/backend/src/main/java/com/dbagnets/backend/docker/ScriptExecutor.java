package com.dbagnets.backend.docker;

import com.dbagnets.backend.docker.strategy.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ScriptExecutor {
    private static final Logger log = LoggerFactory.getLogger(ScriptExecutor.class);
    private final DockerService dockerService;

    public ScriptExecutor(DockerService dockerService) {
        this.dockerService = dockerService;
    }

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
            case "elasticsearch", "couchdb", "milvus", "qdrant", "weaviate",
                 "influxdb", "questdb", "dynamodb" -> new HttpApiScriptStrategy(dbName);
            default -> throw new IllegalArgumentException("Unsupported database: " + dbName);
        };
    }
}
