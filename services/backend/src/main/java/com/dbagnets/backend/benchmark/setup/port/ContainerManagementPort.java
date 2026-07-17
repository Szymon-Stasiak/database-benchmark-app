package com.dbagnets.backend.benchmark.setup.port;

import com.dbagnets.backend.infrastructure.docker.ContainerSpec;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface ContainerManagementPort {

    String createAndStartContainer(ContainerSpec spec);

    void stopContainer(String containerId);

    void restartContainer(String containerId);

    void removeContainer(String containerId);

    void hardRemoveContainer(String containerId);

    void removeContainersByNamePrefix(String namePrefix);

    String getContainerLogs(String containerId, int tailLines);

    void streamLogs(String containerId, SseEmitter emitter);

    boolean isContainerRunning(String containerId);

    int findAvailablePort();
}
