package com.dbagnets.backend.engine.driver;

import com.dbagnets.backend.domain.DatabaseEngine;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DriverCoverageTest {

    private static final Map<DatabaseEngine, String> DRIVER_CLASSES = new EnumMap<>(Map.ofEntries(
            Map.entry(DatabaseEngine.POSTGRESQL, "com.dbagnets.backend.engine.driver.pg.PgDriver"),
            Map.entry(DatabaseEngine.TIMESCALEDB, "com.dbagnets.backend.engine.driver.pg.TimescaleDriver"),
            Map.entry(DatabaseEngine.QUESTDB, "com.dbagnets.backend.engine.driver.pg.QuestdbDriver"),
            Map.entry(DatabaseEngine.MYSQL, "com.dbagnets.backend.engine.driver.mysql.MysqlDriver"),
            Map.entry(DatabaseEngine.NEO4J, "com.dbagnets.backend.engine.driver.neo4j.Neo4jDriver"),
            Map.entry(DatabaseEngine.MEMGRAPH, "com.dbagnets.backend.engine.driver.memgraph.MemgraphDriver"),
            Map.entry(DatabaseEngine.MONGODB, "com.dbagnets.backend.engine.driver.mongo.MongoDriver"),
            Map.entry(DatabaseEngine.REDIS, "com.dbagnets.backend.engine.driver.redis.RedisDriver"),
            Map.entry(DatabaseEngine.QDRANT, "com.dbagnets.backend.engine.driver.qdrant.QdrantDriver"),
            Map.entry(DatabaseEngine.ARANGODB, "com.dbagnets.backend.engine.driver.arango.ArangoDriver"),
            Map.entry(DatabaseEngine.WEAVIATE, "com.dbagnets.backend.engine.driver.weaviate.WeaviateDriver"),
            Map.entry(DatabaseEngine.COUCHDB, "com.dbagnets.backend.engine.driver.couchdb.CouchdbDriver"),
            Map.entry(DatabaseEngine.ELASTICSEARCH, "com.dbagnets.backend.engine.driver.elasticsearch.ElasticsearchDriver"),
            Map.entry(DatabaseEngine.INFLUXDB, "com.dbagnets.backend.engine.driver.influx.InfluxdbDriver"),
            Map.entry(DatabaseEngine.ETCD, "com.dbagnets.backend.engine.driver.etcd.EtcdDriver"),
            Map.entry(DatabaseEngine.DYNAMODB, "com.dbagnets.backend.engine.driver.dynamo.DynamodbDriver")
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
