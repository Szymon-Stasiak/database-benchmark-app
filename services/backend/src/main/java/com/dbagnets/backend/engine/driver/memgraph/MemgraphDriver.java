package com.dbagnets.backend.engine.driver.memgraph;

import com.dbagnets.backend.engine.driver.neo4j.BoltDriverBase;
import com.dbagnets.backend.domain.DatabaseEngine;
import org.springframework.stereotype.Component;

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
