package com.dbagnets.backend.benchmark.result.application;

import com.dbagnets.backend.engine.driver.redis.RedisPoolCache;
import com.dbagnets.backend.infrastructure.docker.DockerService;
import com.dbagnets.backend.infrastructure.size.EngineDataDir;
import com.dbagnets.backend.shared.entity.BenchmarkDatabase;
import com.dbagnets.backend.domain.DatabaseEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataSizeProbe {

    private static final Duration CACHE_TTL = Duration.ofSeconds(2);

    private final DockerService dockerService;
    private final RedisPoolCache redisCache;

    private final ConcurrentHashMap<String, CachedSize> cache = new ConcurrentHashMap<>();

    public Long sizeOf(BenchmarkDatabase db, String hostAddress) {
        if (db == null) return null;

        CachedSize cached = cache.get(db.getId());
        if (cached != null && !cached.isExpired()) {
            return cached.bytes();
        }

        Long fresh = probeNow(db, hostAddress);
        cache.put(db.getId(), new CachedSize(fresh, Instant.now()));
        return fresh;
    }

    public void invalidate(String databaseId) {
        cache.remove(databaseId);
    }

    private Long probeNow(BenchmarkDatabase db, String hostAddress) {
        try {
            DatabaseEngine engine = DatabaseEngine.of(db.getDbName());
            if (engine == DatabaseEngine.REDIS) {
                return redisSize(db, hostAddress);
            }
            String dataDir = EngineDataDir.forEngine(engine);
            if (dataDir == null) {
                return null;
            }
            return duSize(db, dataDir);
        } catch (Exception ex) {
            log.warn("Size probe failed for {}: {}", db.getDbName(), ex.getMessage());
            return null;
        }
    }

    private Long duSize(BenchmarkDatabase db, String dataDir) {
        String containerId = db.getContainerId();
        if (containerId == null || containerId.isBlank()) return null;
        String output = dockerService.execInContainer(containerId, "du", "-sb", dataDir);
        return parseDuOutput(output);
    }

    private Long parseDuOutput(String output) {
        if (output == null || output.isBlank()) return null;
        String firstLine = output.split("\r?\n", 2)[0].trim();
        if (firstLine.isEmpty()) return null;
        String token = firstLine.split("\\s+", 2)[0];
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException ex) {
            log.debug("Cannot parse du output token '{}' (full output: {})", token, output);
            return null;
        }
    }

    private Long redisSize(BenchmarkDatabase db, String host) {
        if (db.getHostPort() == null) return null;
        JedisPool pool = redisCache.get(db.getId(), host, db.getHostPort());
        try (Jedis jedis = pool.getResource()) {
            String info = jedis.info("memory");
            for (String line : info.split("\r?\n")) {
                if (line.startsWith("used_memory_rss:")) {
                    return Long.parseLong(line.substring("used_memory_rss:".length()).trim());
                }
            }
        }
        return null;
    }

    public String humanize(Long bytes) {
        if (bytes == null) return "n/a";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb);
        return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0);
    }

    private record CachedSize(Long bytes, Instant capturedAt) {
        boolean isExpired() {
            return Duration.between(capturedAt, Instant.now()).compareTo(CACHE_TTL) > 0;
        }
    }
}
