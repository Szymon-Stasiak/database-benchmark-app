package com.dbagnets.backend.insert.size;

import com.dbagnets.backend.docker.DockerService;

/** Redis is in-memory — report {@code used_memory} from {@code INFO memory}. */
public class RedisSizeStrategy implements DatabaseSizeStrategy {

    @Override
    public long sizeBytes(DockerService docker, String containerId, Integer hostPort) {
        try {
            String out = docker.execInContainer(containerId, "redis-cli", "INFO", "memory");
            if (out == null) return -1;
            for (String line : out.split("\\R")) {
                if (line.startsWith("used_memory:")) {
                    return Long.parseLong(line.substring("used_memory:".length()).trim());
                }
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }
}
