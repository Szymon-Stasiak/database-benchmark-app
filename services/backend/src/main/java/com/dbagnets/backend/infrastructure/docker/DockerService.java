package com.dbagnets.backend.infrastructure.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.dbagnets.backend.benchmark.setup.port.ContainerManagementPort;
import com.dbagnets.backend.infrastructure.sse.SseEvents;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class DockerService implements ContainerManagementPort {

    private static final String DOCKER_SOCKET = "unix:///var/run/docker.sock";
    private static final int HTTP_MAX_CONNECTIONS = 100;
    private static final Duration HTTP_CONNECTION_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration HTTP_RESPONSE_TIMEOUT = Duration.ofSeconds(45);
    private static final long IMAGE_PULL_TIMEOUT_MINUTES = 5;
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final long MEMORY_SWAP_MULTIPLIER = 2;
    private static final int CONTAINER_STOP_TIMEOUT_SECONDS = 10;
    private static final int CONTAINER_ID_SHORT_LENGTH = 12;
    private static final long LOG_FETCH_TIMEOUT_SECONDS = 10;
    private static final int STREAM_LOGS_TAIL_LINES = 50;
    private static final long EXEC_TIMEOUT_SECONDS = 120;

    private DockerClient dockerClient;

    @PostConstruct
    void init() {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(DOCKER_SOCKET)
                .build();
        var httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(HTTP_MAX_CONNECTIONS)
                .connectionTimeout(HTTP_CONNECTION_TIMEOUT)
                .responseTimeout(HTTP_RESPONSE_TIMEOUT)
                .build();
        dockerClient = DockerClientImpl.getInstance(config, httpClient);
    }

    @PreDestroy
    void cleanup() {
        try {
            if (dockerClient != null) dockerClient.close();
        } catch (IOException e) {
            log.warn("Failed to close DockerClient", e);
        }
    }

    public DockerClient getClient() {
        return dockerClient;
    }

    public String createAndStartContainer(ContainerSpec spec) {
        try {
            dockerClient.pullImageCmd(spec.image())
                    .start()
                    .awaitCompletion(IMAGE_PULL_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Image pull failed or timed out for {}, trying local: {}", spec.image(), e.getMessage());
        }

        long memBytes = spec.memoryMb() * BYTES_PER_MB;
        var hostConfig = HostConfig.newHostConfig()
                .withPortBindings(new PortBinding(
                        Ports.Binding.bindPort(spec.hostPort()),
                        ExposedPort.tcp(spec.containerPort())
                ))
                .withMemory(memBytes)
                .withMemorySwap(memBytes * MEMORY_SWAP_MULTIPLIER);

        var envList = spec.environment().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .toList();

        CreateContainerResponse container = dockerClient.createContainerCmd(spec.image())
                .withName(spec.name())
                .withExposedPorts(ExposedPort.tcp(spec.containerPort()))
                .withHostConfig(hostConfig)
                .withEnv(envList)
                .exec();

        dockerClient.startContainerCmd(container.getId()).exec();
        log.info("Started container {} ({})", spec.name(), shortId(container.getId()));
        return container.getId();
    }

    public void stopContainer(String containerId) {
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(CONTAINER_STOP_TIMEOUT_SECONDS).exec();
            log.info("Stopped container {}", shortId(containerId));
        } catch (NotModifiedException e) {
            log.info("Container {} already stopped", shortId(containerId));
        }
    }

    public void restartContainer(String containerId) {
        dockerClient.restartContainerCmd(containerId).withTimeout(CONTAINER_STOP_TIMEOUT_SECONDS).exec();
        log.info("Restarted container {}", shortId(containerId));
    }

    public void removeContainer(String containerId) {
        dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        log.info("Removed container {}", shortId(containerId));
    }

    public void hardRemoveContainer(String containerId) {
        try {
            dockerClient.removeContainerCmd(containerId)
                    .withForce(true)
                    .withRemoveVolumes(true)
                    .exec();
            log.info("Hard-removed container {} (with volumes)", shortId(containerId));
        } catch (Exception e) {
            log.warn("Hard remove failed for {}: {}", containerId, e.getMessage());
        }
    }

    public void removeContainersByNamePrefix(String namePrefix) {
        try {
            var containers = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withNameFilter(List.of(namePrefix))
                    .exec();
            for (var c : containers) {
                String matched = null;
                if (c.getNames() != null) {
                    for (var name : c.getNames()) {
                        String cleaned = name != null && name.startsWith("/") ? name.substring(1) : name;
                        if (cleaned != null && cleaned.startsWith(namePrefix)) {
                            matched = cleaned;
                            break;
                        }
                    }
                }
                if (matched == null) continue;
                try {
                    dockerClient.removeContainerCmd(c.getId())
                            .withForce(true)
                            .withRemoveVolumes(true)
                            .exec();
                    log.info("Removed orphan container {} ({})", matched, shortId(c.getId()));
                } catch (Exception e) {
                    log.warn("Failed to remove orphan {}: {}", matched, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list containers for prefix {}: {}", namePrefix, e.getMessage());
        }
    }

    public String getContainerLogs(String containerId, int tailLines) {
        var sb = new StringBuilder();
        try {
            dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTail(tailLines)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            sb.append(new String(frame.getPayload()));
                        }
                    })
                    .awaitCompletion(LOG_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return sb.toString();
    }

    public void streamLogs(String containerId, SseEmitter emitter) {
        dockerClient.logContainerCmd(containerId)
                .withStdOut(true)
                .withStdErr(true)
                .withFollowStream(true)
                .withTail(STREAM_LOGS_TAIL_LINES)
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        try {
                            emitter.send(SseEmitter.event()
                                    .name(SseEvents.EVENT_LOG)
                                    .data(new String(frame.getPayload())));
                        } catch (IOException e) {
                            try {
                                close();
                            } catch (IOException ex) { /* ignore */ }
                        }
                    }

                    @Override
                    public void onComplete() {
                        emitter.complete();
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        emitter.completeWithError(throwable);
                    }
                });
    }

    public int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("No available port found", e);
        }
    }

    public String execInContainer(String containerId, String... command) {
        var execCreate = dockerClient.execCreateCmd(containerId)
                .withCmd(command)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

        var sb = new StringBuilder();
        try {
            dockerClient.execStartCmd(execCreate.getId())
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            sb.append(new String(frame.getPayload()));
                        }
                    })
                    .awaitCompletion(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return sb.toString();
    }

    public String execWithStdin(String containerId, String stdin, String... command) {
        var execCreate = dockerClient.execCreateCmd(containerId)
                .withCmd(command)
                .withAttachStdin(true)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

        var sb = new StringBuilder();
        try {
            var inputStream = new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8));
            dockerClient.execStartCmd(execCreate.getId())
                    .withStdIn(inputStream)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            sb.append(new String(frame.getPayload()));
                        }
                    })
                    .awaitCompletion(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return sb.toString();
    }

    private String shortId(String containerId) {
        return containerId.substring(0, CONTAINER_ID_SHORT_LENGTH);
    }
}
