package com.dbagnets.backend.engine.driver.engines.neo4j;

import org.springframework.stereotype.Component;

import com.dbagnets.backend.domain.DatabaseEngine;

@Component
public class Neo4jDriver extends BoltDriverBase {

    public Neo4jDriver(Neo4jDriverCache driverCache) {
        super(driverCache);
    }

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.NEO4J;
    }
}
