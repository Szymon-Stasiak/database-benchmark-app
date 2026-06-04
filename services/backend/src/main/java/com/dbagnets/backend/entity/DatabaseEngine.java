package com.dbagnets.backend.entity;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;

public enum DatabaseEngine {
    POSTGRESQL("postgresql", 5432, v -> "postgres:" + v,
        Map.of("POSTGRES_PASSWORD", "benchmark", "POSTGRES_DB", "benchmark")),
    TIMESCALEDB("timescaledb", 5432, v -> "timescale/timescaledb:latest-pg17",
        Map.of("POSTGRES_PASSWORD", "benchmark", "POSTGRES_DB", "benchmark")),
    MYSQL("mysql", 3306, v -> "mysql:" + v,
        Map.of("MYSQL_ROOT_PASSWORD", "root", "MYSQL_DATABASE", "benchmark")),
    NEO4J("neo4j", 7687, v -> "neo4j:" + v,
        Map.of(
            "NEO4J_AUTH", "neo4j/benchmark",
            "NEO4J_server_memory_heap_initial__size", "256m",
            "NEO4J_server_memory_heap_max__size", "512m",
            "NEO4J_server_memory_pagecache_size", "128m"
        )),
    MEMGRAPH("memgraph", 7687, v -> "memgraph/memgraph:" + v,
        Map.of("MEMGRAPH_USER", "memgraph", "MEMGRAPH_PASSWORD", "memgraph")),
    MONGODB("mongodb", 27017, v -> "mongo:" + v, Map.of()),
    REDIS("redis", 6379, v -> "redis:" + v, Map.of()),
    ARANGODB("arangodb", 8529, v -> "arangodb:" + v,
        Map.of("ARANGO_ROOT_PASSWORD", "root")),
    ELASTICSEARCH("elasticsearch", 9200, v -> "docker.elastic.co/elasticsearch/elasticsearch:" + v + ".0",
        Map.of("discovery.type", "single-node", "xpack.security.enabled", "false", "ES_JAVA_OPTS", "-Xms256m -Xmx256m")),
    COUCHDB("couchdb", 5984, v -> "couchdb:" + v,
        Map.of("COUCHDB_USER", "admin", "COUCHDB_PASSWORD", "benchmark")),
    MILVUS("milvus", 19530, DatabaseEngine::milvusImage, Map.of()),
    QDRANT("qdrant", 6333, v -> "qdrant/qdrant:v" + v, Map.of()),
    WEAVIATE("weaviate", 8080, v -> "semitechnologies/weaviate:" + v,
        Map.of("AUTHENTICATION_ANONYMOUS_ACCESS_ENABLED", "true", "PERSISTENCE_DATA_PATH", "/var/lib/weaviate")),
    INFLUXDB("influxdb", 8086, v -> "influxdb:" + v,
        Map.of(
            "DOCKER_INFLUXDB_INIT_MODE", "setup",
            "DOCKER_INFLUXDB_INIT_USERNAME", "admin",
            "DOCKER_INFLUXDB_INIT_PASSWORD", "benchmark",
            "DOCKER_INFLUXDB_INIT_ORG", "benchmark",
            "DOCKER_INFLUXDB_INIT_BUCKET", "benchmark"
        )),
    QUESTDB("questdb", 9000, v -> "questdb/questdb:" + v,
        Map.of("QDB_CAIRO_COMMIT_LAG", "1000")),
    DYNAMODB("dynamodb", 8000, v -> "amazon/dynamodb-local:latest", Map.of()),
    ETCD("etcd", 2379, v -> "bitnami/etcd:" + v,
        Map.of("ETCD_ADVERTISE_CLIENT_URLS", "http://0.0.0.0:2379", "ETCD_LISTEN_CLIENT_URLS", "http://0.0.0.0:2379"));

    private final String dbName;
    private final int port;
    private final Function<String, String> imageBuilder;
    private final Map<String, String> env;

    DatabaseEngine(String dbName, int port, Function<String, String> imageBuilder, Map<String, String> env) {
        this.dbName = dbName;
        this.port = port;
        this.imageBuilder = imageBuilder;
        this.env = env;
    }

    public int port() {
        return port;
    }

    public String dockerImage(String version) {
        return imageBuilder.apply(version);
    }

    public Map<String, String> env() {
        return env;
    }

    public static DatabaseEngine of(String dbName) {
        return Arrays.stream(values())
            .filter(e -> e.dbName.equalsIgnoreCase(dbName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown database engine: " + dbName));
    }

    private static final Map<String, String> MILVUS_TAGS = Map.of(
        "2.4", "milvusdb/milvus:v2.4.24",
        "2.3", "milvusdb/milvus:v2.3.22"
    );

    private static String milvusImage(String version) {
        return MILVUS_TAGS.getOrDefault(version, "milvusdb/milvus:v" + version);
    }
}