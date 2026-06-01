package com.dbagnets.backend.benchmark.driver.neo4j;

import com.dbagnets.backend.benchmark.driver.ConnectionCache;
import jakarta.annotation.PreDestroy;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class Neo4jDriverCache implements ConnectionCache {

    private final Map<String, Driver> drivers = new ConcurrentHashMap<>();

    public Driver get(String databaseId, String host, int port) {
        return drivers.computeIfAbsent(databaseId,
                id -> GraphDatabase.driver("bolt://" + host + ":" + port, AuthTokens.basic("neo4j", "benchmark")));
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
