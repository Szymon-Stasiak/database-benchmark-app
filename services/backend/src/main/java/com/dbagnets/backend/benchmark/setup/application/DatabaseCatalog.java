package com.dbagnets.backend.benchmark.setup.application;

import com.dbagnets.backend.benchmark.setup.api.dto.SupportedDatabasesResponse;
import com.dbagnets.backend.benchmark.setup.api.dto.SupportedDatabasesResponse.DatabaseOption;
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
            new DatabaseOption("mysql", "MySQL", List.of("9.0", "8.4", "8.0"))
        ));
        CATALOG.put("GRAPH", List.of(
            new DatabaseOption("neo4j", "Neo4j", List.of("5.26", "5.25", "5.24")),
            new DatabaseOption("arangodb", "ArangoDB", List.of("3.12", "3.11")),
            new DatabaseOption("memgraph", "Memgraph", List.of("2.22.0", "2.21.0"))
        ));
        CATALOG.put("VECTOR", List.of(
            new DatabaseOption("qdrant", "Qdrant", List.of("1.12.6", "1.11.5")),
            new DatabaseOption("weaviate", "Weaviate", List.of("1.27.10", "1.26.0"))
        ));
        CATALOG.put("DOCUMENT", List.of(
            new DatabaseOption("mongodb", "MongoDB", List.of("8.0", "7.0", "6.0")),
            new DatabaseOption("couchdb", "CouchDB", List.of("3.4", "3.3")),
            new DatabaseOption("elasticsearch", "Elasticsearch", List.of("8.16", "8.15"))
        ));
        CATALOG.put("KEY_VALUE", List.of(
            new DatabaseOption("redis", "Redis", List.of("7.4", "7.2")),
            new DatabaseOption("dynamodb", "DynamoDB Local", List.of("2.5", "2.4")),
            new DatabaseOption("etcd", "etcd", List.of("3.5.16", "3.5.0"))
        ));
        CATALOG.put("TIME_SERIES", List.of(
            new DatabaseOption("timescaledb", "TimescaleDB", List.of("2.17", "2.16")),
            new DatabaseOption("influxdb", "InfluxDB", List.of("2.7", "2.6")),
            new DatabaseOption("questdb", "QuestDB", List.of("8.2.3", "8.1.4"))
        ));
    }

    public SupportedDatabasesResponse getSupportedDatabases() {
        return new SupportedDatabasesResponse(CATALOG);
    }
}