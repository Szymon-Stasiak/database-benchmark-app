package com.dbagnets.backend.benchmark.driver;

import com.dbagnets.backend.entity.DatabaseEngine;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DriverCoverageTest {

    private static final Map<DatabaseEngine, String> DRIVER_CLASSES = new EnumMap<>(Map.ofEntries(
            Map.entry(DatabaseEngine.POSTGRESQL, "com.dbagnets.backend.benchmark.driver.pg.PgDriver"),
            Map.entry(DatabaseEngine.TIMESCALEDB, "com.dbagnets.backend.benchmark.driver.pg.TimescaleDriver"),
            Map.entry(DatabaseEngine.QUESTDB, "com.dbagnets.backend.benchmark.driver.pg.QuestdbDriver"),
            Map.entry(DatabaseEngine.MYSQL, "com.dbagnets.backend.benchmark.driver.mysql.MysqlDriver"),
            Map.entry(DatabaseEngine.NEO4J, "com.dbagnets.backend.benchmark.driver.neo4j.Neo4jDriver"),
            Map.entry(DatabaseEngine.MEMGRAPH, "com.dbagnets.backend.benchmark.driver.memgraph.MemgraphDriver"),
            Map.entry(DatabaseEngine.MONGODB, "com.dbagnets.backend.benchmark.driver.mongo.MongoDriver"),
            Map.entry(DatabaseEngine.REDIS, "com.dbagnets.backend.benchmark.driver.redis.RedisDriver"),
            Map.entry(DatabaseEngine.QDRANT, "com.dbagnets.backend.benchmark.driver.qdrant.QdrantDriver"),
            Map.entry(DatabaseEngine.ARANGODB, "com.dbagnets.backend.benchmark.driver.arango.ArangoDriver"),
            Map.entry(DatabaseEngine.WEAVIATE, "com.dbagnets.backend.benchmark.driver.weaviate.WeaviateDriver"),
            Map.entry(DatabaseEngine.COUCHDB, "com.dbagnets.backend.benchmark.driver.couchdb.CouchdbDriver"),
            Map.entry(DatabaseEngine.ELASTICSEARCH, "com.dbagnets.backend.benchmark.driver.elasticsearch.ElasticsearchDriver"),
            Map.entry(DatabaseEngine.INFLUXDB, "com.dbagnets.backend.benchmark.driver.influx.InfluxdbDriver"),
            Map.entry(DatabaseEngine.ETCD, "com.dbagnets.backend.benchmark.driver.etcd.EtcdDriver"),
            Map.entry(DatabaseEngine.DYNAMODB, "com.dbagnets.backend.benchmark.driver.dynamo.DynamodbDriver")
    ));

    @Test
    void everyEngineHasDriverClassOnClasspath() throws Exception {
        for (DatabaseEngine engine : DatabaseEngine.values()) {
            String className = DRIVER_CLASSES.get(engine);
            assertThat(className)
                    .as("Missing driver mapping for engine " + engine)
                    .isNotNull();
            Class<?> driverClass = Class.forName(className);
            assertThat(EngineDriver.class.isAssignableFrom(driverClass))
                    .as("%s must implement EngineDriver", className)
                    .isTrue();
            assertThat(driverClass.isAnnotationPresent(org.springframework.stereotype.Component.class))
                    .as("%s must be a Spring @Component", className)
                    .isTrue();
        }
    }
}
