package com.dbagnets.backend.benchmark.driver.memgraph;

import com.dbagnets.backend.benchmark.driver.neo4j.BoltDriverBase;
import com.dbagnets.backend.entity.DatabaseEngine;
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
