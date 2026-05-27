package com.dbagnets.backend.insert.strategy;

import com.dbagnets.backend.insert.strategy.jdbc.MysqlJdbcStrategy;
import com.dbagnets.backend.insert.strategy.jdbc.PostgresJdbcStrategy;
import com.dbagnets.backend.insert.strategy.jdbc.SqliteJdbcStrategy;
import com.dbagnets.backend.insert.strategy.jdbc.TimescaleJdbcStrategy;
import com.dbagnets.backend.insert.strategy.mongo.MongoNativeStrategy;
import com.dbagnets.backend.insert.strategy.neo4j.Neo4jNativeStrategy;
import com.dbagnets.backend.insert.strategy.redis.RedisJedisStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Maps a database name to the appropriate {@link DatabaseInsertStrategy}.
 *
 * <p>Routing rules:
 * <ul>
 *   <li>Native clients (JDBC, Bolt, Mongo sync, Jedis) for the core relational/document/graph/
 *       key-value engines — these are the strategies that time only the in-driver call.</li>
 *   <li>Legacy {@code docker exec}-based strategies for the HTTP-fronted DBs (Arango, CouchDB,
 *       Elasticsearch, InfluxDB, Qdrant, Weaviate, etcd) until they get migrated to native HTTP
 *       clients hitting {@code hostPort} directly.</li>
 *   <li>Anything else falls back to {@link UnsupportedInsertStrategy} so the run fails cleanly
 *       with a readable message instead of crashing.</li>
 * </ul>
 */
@Component
public class InsertStrategyFactory {

    private final ObjectMapper mapper;

    public InsertStrategyFactory(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public DatabaseInsertStrategy create(String dbName) {
        if (dbName == null) return new UnsupportedInsertStrategy("<null>");
        return switch (dbName.toLowerCase(Locale.ROOT)) {
            // Native client paths (Slice C / D)
            case "postgresql" -> new PostgresJdbcStrategy();
            case "timescaledb" -> new TimescaleJdbcStrategy();
            case "mysql" -> new MysqlJdbcStrategy();
            case "sqlite" -> new SqliteJdbcStrategy();
            case "mongodb" -> new MongoNativeStrategy();
            case "neo4j" -> new Neo4jNativeStrategy("bolt", "neo4j", "benchmark");
            case "memgraph" -> new Neo4jNativeStrategy("bolt", "memgraph", "memgraph");
            case "redis" -> new RedisJedisStrategy(mapper);

            // Still on docker-exec (HTTP-fronted DBs — migrate in a follow-up)
            case "etcd" -> new EtcdInsertStrategy(mapper);
            case "arangodb" -> new ArangoInsertStrategy(mapper);
            case "couchdb" -> new CouchdbInsertStrategy(mapper);
            case "elasticsearch" -> new ElasticsearchInsertStrategy(mapper);
            case "influxdb" -> new InfluxdbInsertStrategy(mapper);
            case "qdrant" -> new QdrantInsertStrategy(mapper);
            case "weaviate" -> new WeaviateInsertStrategy(mapper);

            default -> new UnsupportedInsertStrategy(dbName);
        };
    }
}
