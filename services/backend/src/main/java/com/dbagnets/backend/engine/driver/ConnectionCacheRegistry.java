package com.dbagnets.backend.engine.driver;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectionCacheRegistry {

    private final List<ConnectionCache> caches;

    public void evictAll(String databaseId) {
        for (ConnectionCache cache : caches) {
            try {
                cache.evict(databaseId);
            } catch (Exception e) {
                log.warn("Failed to evict {} for database {}: {}", cache.getClass().getSimpleName(), databaseId, e.getMessage());
            }
        }
    }
}
