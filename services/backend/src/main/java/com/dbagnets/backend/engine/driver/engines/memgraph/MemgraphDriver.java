package com.dbagnets.backend.engine.driver.engines.memgraph;

import org.springframework.stereotype.Component;

import com.dbagnets.backend.domain.DatabaseEngine;
import com.dbagnets.backend.engine.driver.engines.neo4j.BoltDriverBase;

@Component
public class MemgraphDriver extends BoltDriverBase {

    public MemgraphDriver(MemgraphDriverCache driverCache) {
        super(driverCache);
    }

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.MEMGRAPH;
    }
}
