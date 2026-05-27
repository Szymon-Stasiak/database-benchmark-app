package com.dbagnets.backend.insert.size;

import com.dbagnets.backend.docker.DockerService;

public class UnknownSizeStrategy implements DatabaseSizeStrategy {
    @Override
    public long sizeBytes(DockerService docker, String containerId, Integer hostPort) {
        return -1;
    }
}
