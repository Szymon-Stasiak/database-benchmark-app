package com.dbagnets.backend.engine.driver;

public interface ConnectionCache {
    void evict(String databaseId);
}
