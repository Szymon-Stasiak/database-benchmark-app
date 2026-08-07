package com.dbagnets.backend.engine.driver.engines.memgraph;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PreDestroy;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.stereotype.Component;

import com.dbagnets.backend.engine.driver.engines.neo4j.BoltDriverProvider;
import com.dbagnets.backend.engine.driver.support.ConnectionCache;

@Component
public class MemgraphDriverCache implements ConnectionCache, BoltDriverProvider {

    private final Map<String, Driver> drivers = new ConcurrentHashMap<>();

    @Override
    public Driver get(String databaseId, String host, int port) {
        return drivers.computeIfAbsent(
                databaseId,
                id -> GraphDatabase.driver("bolt://" + host + ":" + port, AuthTokens.none()));
    }

    @Override
    public void evict(String databaseId) {
        Driver driver = drivers.remove(databaseId);
        if (driver != null) {
            driver.close();
        }
    }

    @PreDestroy
    public void shutdown() {
        drivers.values().forEach(Driver::close);
        drivers.clear();
    }
}
