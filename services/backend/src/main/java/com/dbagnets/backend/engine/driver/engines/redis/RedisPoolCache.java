package com.dbagnets.backend.engine.driver.engines.redis;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import com.dbagnets.backend.engine.driver.support.ConnectionCache;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Component
public class RedisPoolCache implements ConnectionCache {

    private final Map<String, JedisPool> pools = new ConcurrentHashMap<>();

    public JedisPool get(String databaseId, String host, int port) {
        return pools.computeIfAbsent(
                databaseId,
                id -> {
                    JedisPoolConfig config = new JedisPoolConfig();
                    config.setMaxTotal(8);
                    config.setMaxIdle(4);
                    return new JedisPool(config, host, port, 5_000);
                });
    }

    @Override
    public void evict(String databaseId) {
        JedisPool pool = pools.remove(databaseId);
        if (pool != null) {
            pool.close();
        }
    }

    @PreDestroy
    public void shutdown() {
        pools.values().forEach(JedisPool::close);
        pools.clear();
    }
}
