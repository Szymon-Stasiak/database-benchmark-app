package com.dbagnets.backend.engine.driver.engines.mongo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import com.dbagnets.backend.engine.driver.support.ConnectionCache;

@Component
public class MongoClientCache implements ConnectionCache {

    private final Map<String, MongoClient> clients = new ConcurrentHashMap<>();

    public MongoClient get(String databaseId, String host, int port) {
        return clients.computeIfAbsent(
                databaseId, id -> MongoClients.create("mongodb://" + host + ":" + port));
    }

    @Override
    public void evict(String databaseId) {
        MongoClient client = clients.remove(databaseId);
        if (client != null) {
            client.close();
        }
    }

    @PreDestroy
    public void shutdown() {
        clients.values().forEach(MongoClient::close);
        clients.clear();
    }
}
