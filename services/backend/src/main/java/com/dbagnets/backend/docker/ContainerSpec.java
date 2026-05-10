package com.dbagnets.backend.docker;

import java.util.Map;

public record ContainerSpec(
    String image,
    String name,
    int containerPort,
    int hostPort,
    Map<String, String> environment,
    long memoryMb
) {
    public ContainerSpec(String image, String name, int containerPort, int hostPort, Map<String, String> environment) {
        this(image, name, containerPort, hostPort, environment, 512);
    }
}
