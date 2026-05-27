package com.dbagnets.backend.insert.size;

import com.dbagnets.backend.docker.DockerService;

/**
 * Reports the current on-disk (or in-memory) size of one database container in bytes.
 * Implementations must NOT block longer than a few seconds — callers fan-out across all
 * databases of a benchmark.
 */
public interface DatabaseSizeStrategy {

    /**
     * @return size in bytes, or {@code -1} when size could not be measured (the caller
     *         should surface the failure to the UI).
     */
    long sizeBytes(DockerService docker, String containerId, Integer hostPort);
}
