package com.dbagnets.backend.engine.driver.support;

public interface ConnectionCache {
    void evict(String databaseId);
}
