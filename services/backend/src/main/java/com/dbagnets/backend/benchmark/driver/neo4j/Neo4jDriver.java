package com.dbagnets.backend.benchmark.driver.neo4j;

import com.dbagnets.backend.entity.DatabaseEngine;
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
