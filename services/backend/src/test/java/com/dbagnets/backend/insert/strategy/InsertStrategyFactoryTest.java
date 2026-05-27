package com.dbagnets.backend.insert.strategy;

import com.dbagnets.backend.insert.strategy.jdbc.MysqlJdbcStrategy;
import com.dbagnets.backend.insert.strategy.jdbc.PostgresJdbcStrategy;
import com.dbagnets.backend.insert.strategy.jdbc.SqliteJdbcStrategy;
import com.dbagnets.backend.insert.strategy.jdbc.TimescaleJdbcStrategy;
import com.dbagnets.backend.insert.strategy.mongo.MongoNativeStrategy;
import com.dbagnets.backend.insert.strategy.neo4j.Neo4jNativeStrategy;
import com.dbagnets.backend.insert.strategy.redis.RedisJedisStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InsertStrategyFactoryTest {

    private final InsertStrategyFactory factory = new InsertStrategyFactory(new ObjectMapper());

    @Test
    void nativeClientsForCoreEngines() {
        assertInstanceOf(PostgresJdbcStrategy.class, factory.create("postgresql"));
        assertInstanceOf(TimescaleJdbcStrategy.class, factory.create("timescaledb"));
        assertInstanceOf(MysqlJdbcStrategy.class, factory.create("mysql"));
        assertInstanceOf(SqliteJdbcStrategy.class, factory.create("sqlite"));
        assertInstanceOf(MongoNativeStrategy.class, factory.create("mongodb"));
        assertInstanceOf(Neo4jNativeStrategy.class, factory.create("neo4j"));
        assertInstanceOf(Neo4jNativeStrategy.class, factory.create("memgraph"));
        assertInstanceOf(RedisJedisStrategy.class, factory.create("redis"));
    }

    @Test
    void httpFrontedStillOnDockerExec() {
        assertInstanceOf(EtcdInsertStrategy.class, factory.create("etcd"));
        assertInstanceOf(ArangoInsertStrategy.class, factory.create("arangodb"));
        assertInstanceOf(CouchdbInsertStrategy.class, factory.create("couchdb"));
        assertInstanceOf(ElasticsearchInsertStrategy.class, factory.create("elasticsearch"));
        assertInstanceOf(InfluxdbInsertStrategy.class, factory.create("influxdb"));
        assertInstanceOf(QdrantInsertStrategy.class, factory.create("qdrant"));
        assertInstanceOf(WeaviateInsertStrategy.class, factory.create("weaviate"));
    }

    @Test
    void caseInsensitive() {
        assertInstanceOf(PostgresJdbcStrategy.class, factory.create("PostgreSQL"));
        assertInstanceOf(MongoNativeStrategy.class, factory.create("MongoDB"));
    }

    @Test
    void unknownReturnsUnsupported() {
        assertInstanceOf(UnsupportedInsertStrategy.class, factory.create("milvus"));
        assertInstanceOf(UnsupportedInsertStrategy.class, factory.create("questdb"));
        assertInstanceOf(UnsupportedInsertStrategy.class, factory.create("dynamodb"));
        assertInstanceOf(UnsupportedInsertStrategy.class, factory.create("madeup"));
    }

    @Test
    void nullReturnsUnsupported() {
        assertInstanceOf(UnsupportedInsertStrategy.class, factory.create(null));
    }
}
