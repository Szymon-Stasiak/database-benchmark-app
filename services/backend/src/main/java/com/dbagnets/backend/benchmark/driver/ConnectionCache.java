package com.dbagnets.backend.benchmark.driver;

public interface ConnectionCache {
    void evict(String databaseId);
}
