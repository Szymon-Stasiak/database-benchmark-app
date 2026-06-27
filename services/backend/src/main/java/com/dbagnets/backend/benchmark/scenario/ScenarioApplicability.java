package com.dbagnets.backend.benchmark.scenario;

import com.dbagnets.backend.entity.DatabaseEngine;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class ScenarioApplicability {

    private static final Map<ScenarioType, Set<DatabaseEngine>> SUPPORT = buildSupportMatrix();

    private ScenarioApplicability() {
    }

    public static boolean isApplicable(ScenarioType type, DatabaseEngine engine) {
        Set<DatabaseEngine> supported = SUPPORT.get(type);
        return supported != null && supported.contains(engine);
    }

    public static Set<DatabaseEngine> applicableEngines(ScenarioType type) {
        return SUPPORT.getOrDefault(type, Set.of());
    }

    private static Map<ScenarioType, Set<DatabaseEngine>> buildSupportMatrix() {
        EnumMap<ScenarioType, Set<DatabaseEngine>> map = new EnumMap<>(ScenarioType.class);
        map.put(ScenarioType.AGGREGATE_GROUP_COUNT, EnumSet.of(
                DatabaseEngine.POSTGRESQL,
                DatabaseEngine.TIMESCALEDB,
                DatabaseEngine.QUESTDB,
                DatabaseEngine.MYSQL,
                DatabaseEngine.MONGODB,
                DatabaseEngine.NEO4J,
                DatabaseEngine.MEMGRAPH
        ));
        map.put(ScenarioType.RANGE_FILTER, EnumSet.of(
                DatabaseEngine.POSTGRESQL,
                DatabaseEngine.TIMESCALEDB,
                DatabaseEngine.QUESTDB,
                DatabaseEngine.MYSQL,
                DatabaseEngine.MONGODB,
                DatabaseEngine.NEO4J,
                DatabaseEngine.MEMGRAPH
        ));
        map.put(ScenarioType.GRAPH_TRAVERSAL, EnumSet.of(
                DatabaseEngine.POSTGRESQL,
                DatabaseEngine.MYSQL,
                DatabaseEngine.MONGODB,
                DatabaseEngine.NEO4J,
                DatabaseEngine.MEMGRAPH
        ));
        map.put(ScenarioType.VECTOR_KNN, EnumSet.of(
                DatabaseEngine.QDRANT
        ));
        return map;
    }
}
