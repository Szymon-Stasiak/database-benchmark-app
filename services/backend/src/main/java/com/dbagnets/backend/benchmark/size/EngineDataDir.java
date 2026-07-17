package com.dbagnets.backend.benchmark.size;

import com.dbagnets.backend.domain.DatabaseEngine;

import java.util.EnumMap;
import java.util.Map;

public final class EngineDataDir {

    private static final Map<DatabaseEngine, String> DATA_DIRS = new EnumMap<>(DatabaseEngine.class);

    static {
        DATA_DIRS.put(DatabaseEngine.POSTGRESQL, "/var/lib/postgresql/data");
        DATA_DIRS.put(DatabaseEngine.TIMESCALEDB, "/var/lib/postgresql/data");
        DATA_DIRS.put(DatabaseEngine.MYSQL, "/var/lib/mysql");
        DATA_DIRS.put(DatabaseEngine.MONGODB, "/data/db");
        DATA_DIRS.put(DatabaseEngine.NEO4J, "/data");
        DATA_DIRS.put(DatabaseEngine.MEMGRAPH, "/var/lib/memgraph");
        DATA_DIRS.put(DatabaseEngine.QDRANT, "/qdrant/storage");
        DATA_DIRS.put(DatabaseEngine.WEAVIATE, "/var/lib/weaviate");
        DATA_DIRS.put(DatabaseEngine.ELASTICSEARCH, "/usr/share/elasticsearch/data");
        DATA_DIRS.put(DatabaseEngine.COUCHDB, "/opt/couchdb/data");
        DATA_DIRS.put(DatabaseEngine.ARANGODB, "/var/lib/arangodb3");
        DATA_DIRS.put(DatabaseEngine.DYNAMODB, "/home/dynamodblocal");
        DATA_DIRS.put(DatabaseEngine.ETCD, "/etcd-data");
        DATA_DIRS.put(DatabaseEngine.INFLUXDB, "/var/lib/influxdb2");
        DATA_DIRS.put(DatabaseEngine.QUESTDB, "/var/lib/questdb");
    }

    private EngineDataDir() {
    }

    public static String forEngine(DatabaseEngine engine) {
        return DATA_DIRS.get(engine);
    }
}
