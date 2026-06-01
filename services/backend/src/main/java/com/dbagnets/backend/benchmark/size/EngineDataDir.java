package com.dbagnets.backend.benchmark.size;

import com.dbagnets.backend.entity.DatabaseEngine;

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
        DATA_DIRS.put(DatabaseEngine.ELASTICSEARCH, "/usr/share/elasticsearch/data");
    }

    private EngineDataDir() {
    }

    public static String forEngine(DatabaseEngine engine) {
        return DATA_DIRS.get(engine);
    }
}
