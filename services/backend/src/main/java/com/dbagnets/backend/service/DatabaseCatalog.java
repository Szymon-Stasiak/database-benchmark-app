package com.dbagnets.backend.service;

import com.dbagnets.backend.model.SupportedDatabasesResponse;
import com.dbagnets.backend.model.SupportedDatabasesResponse.DatabaseOption;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DatabaseCatalog {

    private static final Map<String, List<DatabaseOption>> CATALOG;

    static {
        CATALOG = new LinkedHashMap<>();
        CATALOG.put("RELATIONAL", List.of(
            new DatabaseOption("postgresql", "PostgreSQL", List.of("17", "16", "15")),
            new DatabaseOption("mysql", "MySQL", List.of("9.0", "8.4", "8.0")),
            new DatabaseOption("sqlite", "SQLite", List.of("3"))
        ));
        CATALOG.put("GRAPH", List.of(
            new DatabaseOption("neo4j", "Neo4j", List.of("5.26", "5.25", "5.24")),
            new DatabaseOption("arangodb", "ArangoDB", List.of("3.12", "3.11")),
            new DatabaseOption("memgraph", "Memgraph", List.of("2.21", "2.20"))
        ));
        CATALOG.put("VECTOR", List.of(
            new DatabaseOption("milvus", "Milvus", List.of("2.4", "2.3")),
            new DatabaseOption("qdrant", "Qdrant", List.of("1.12", "1.11")),
            new DatabaseOption("weaviate", "Weaviate", List.of("1.27", "1.26"))
        ));
        CATALOG.put("DOCUMENT", List.of(
            new DatabaseOption("mongodb", "MongoDB", List.of("8.0", "7.0", "6.0")),
            new DatabaseOption("couchdb", "CouchDB", List.of("3.4", "3.3")),
            new DatabaseOption("elasticsearch", "Elasticsearch", List.of("8.16", "8.15"))
        ));
        CATALOG.put("KEY_VALUE", List.of(
            new DatabaseOption("redis", "Redis", List.of("7.4", "7.2")),
            new DatabaseOption("dynamodb", "DynamoDB Local", List.of("2.5", "2.4")),
            new DatabaseOption("etcd", "etcd", List.of("3.5", "3.4"))
        ));
        CATALOG.put("TIME_SERIES", List.of(
            new DatabaseOption("timescaledb", "TimescaleDB", List.of("2.17", "2.16")),
            new DatabaseOption("influxdb", "InfluxDB", List.of("2.7", "2.6")),
            new DatabaseOption("questdb", "QuestDB", List.of("8.2", "8.1"))
        ));
    }

    public SupportedDatabasesResponse getSupportedDatabases() {
        return new SupportedDatabasesResponse(CATALOG);
    }

    public boolean isSupported(String dbType, String dbName, String dbVersion) {
        var options = CATALOG.get(dbType.toUpperCase());
        if (options == null) return false;
        return options.stream()
            .anyMatch(opt -> opt.name().equals(dbName) && opt.versions().contains(dbVersion));
    }
}
