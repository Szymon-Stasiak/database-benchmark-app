package com.dbagnets.backend.insert.size;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Maps a database name to the appropriate size-probing strategy.
 * Adding a new database = one extra {@code case} below.
 */
@Component
public class DatabaseSizeStrategyFactory {

    public DatabaseSizeStrategy create(String dbName) {
        if (dbName == null) return new UnknownSizeStrategy();
        return switch (dbName.toLowerCase(Locale.ROOT)) {
            case "postgresql", "timescaledb" -> new DuBasedSizeStrategy("/var/lib/postgresql/data");
            case "mysql" -> new DuBasedSizeStrategy("/var/lib/mysql");
            case "mongodb" -> new DuBasedSizeStrategy("/data/db");
            case "neo4j" -> new DuBasedSizeStrategy("/data");
            case "memgraph" -> new DuBasedSizeStrategy("/var/lib/memgraph");
            case "redis" -> new RedisSizeStrategy();
            case "etcd" -> new EtcdSizeStrategy();
            case "arangodb" -> new DuBasedSizeStrategy("/var/lib/arangodb3");
            case "couchdb" -> new DuBasedSizeStrategy("/opt/couchdb/data");
            case "elasticsearch" -> new DuBasedSizeStrategy("/usr/share/elasticsearch/data");
            case "influxdb" -> new DuBasedSizeStrategy("/var/lib/influxdb2");
            case "questdb" -> new DuBasedSizeStrategy("/var/lib/questdb");
            case "qdrant" -> new DuBasedSizeStrategy("/qdrant/storage");
            case "weaviate" -> new DuBasedSizeStrategy("/var/lib/weaviate");
            case "milvus" -> new DuBasedSizeStrategy("/var/lib/milvus");
            case "dynamodb" -> new DuBasedSizeStrategy("/home/dynamodblocal/data");
            default -> new UnknownSizeStrategy();
        };
    }
}
