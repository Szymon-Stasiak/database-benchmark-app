package com.dbagnets.backend.benchmark.setup.port;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.dbagnets.backend.infrastructure.docker.ContainerSpec;

public interface ContainerManagementPort {

    String createAndStartContainer(ContainerSpec spec);

    void stopContainer(String containerId);

    void restartContainer(String containerId);

    void removeContainer(String containerId);

    void hardRemoveContainer(String containerId);

    void removeContainersByNamePrefix(String namePrefix);

    String getContainerLogs(String containerId, int tailLines);

    void streamLogs(String containerId, SseEmitter emitter);

    int findAvailablePort();
}
