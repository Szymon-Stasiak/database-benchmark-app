package com.dbagnets.backend.insert.strategy;

import com.dbagnets.backend.docker.DockerService;

public class UnsupportedInsertStrategy implements DatabaseInsertStrategy {

    private final String reason;

    public UnsupportedInsertStrategy(String dbName) {
        this.reason = "Insert benchmarking is not supported for '" + dbName
            + "' in this version. Please use one of: postgresql, mysql, timescaledb, sqlite, mongodb, "
            + "neo4j, memgraph, redis, etcd, arangodb, couchdb, elasticsearch, qdrant, weaviate, influxdb.";
    }

    @Override
    public InsertOutcome insert(DockerService docker, InsertContext context) {
        return InsertOutcome.failure(reason, 0);
    }
}
