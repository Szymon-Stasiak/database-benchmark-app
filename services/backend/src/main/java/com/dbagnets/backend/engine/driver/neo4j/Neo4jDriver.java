package com.dbagnets.backend.engine.driver.neo4j;

import com.dbagnets.backend.domain.DatabaseEngine;
import org.springframework.stereotype.Component;

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
