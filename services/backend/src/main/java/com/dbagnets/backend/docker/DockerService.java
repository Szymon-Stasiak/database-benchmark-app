package com.dbagnets.backend.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.github.dockerjava.api.async.ResultCallback;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class DockerService {
    private static final Logger log = LoggerFactory.getLogger(DockerService.class);
    private DockerClient dockerClient;

    @PostConstruct
    void init() {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost("unix:///var/run/docker.sock")
            .build();
        var httpClient = new ZerodepDockerHttpClient.Builder()
            .dockerHost(config.getDockerHost())
            .sslConfig(config.getSSLConfig())
            .maxConnections(100)
            .connectionTimeout(Duration.ofSeconds(30))
            .responseTimeout(Duration.ofSeconds(45))
            .build();
        dockerClient = DockerClientImpl.getInstance(config, httpClient);
    }

    @PreDestroy
    void cleanup() {
        try { if (dockerClient != null) dockerClient.close(); } catch (IOException e) { log.warn("Failed to close DockerClient", e); }
    }

    public DockerClient getClient() { return dockerClient; }

    public String createAndStartContainer(ContainerSpec spec) {
        // Pull image (ignore if already exists)
        try {
            dockerClient.pullImageCmd(spec.image())
                .start()
                .awaitCompletion(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Image pull failed or timed out for {}, trying local: {}", spec.image(), e.getMessage());
        }

        // Create container
        long memBytes = spec.memoryMb() * 1024 * 1024;
        var hostConfig = HostConfig.newHostConfig()
            .withPortBindings(new PortBinding(
                Ports.Binding.bindPort(spec.hostPort()),
                ExposedPort.tcp(spec.containerPort())
            ))
            .withMemory(memBytes)
            .withMemorySwap(memBytes * 2);

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
        log.info("Started container {} ({})", spec.name(), container.getId().substring(0, 12));
        return container.getId();
    }

    public void stopContainer(String containerId) {
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(10).exec();
            log.info("Stopped container {}", containerId.substring(0, 12));
        } catch (NotModifiedException e) {
            log.info("Container {} already stopped", containerId.substring(0, 12));
        }
    }

    public void restartContainer(String containerId) {
        dockerClient.restartContainerCmd(containerId).withTimeout(10).exec();
        log.info("Restarted container {}", containerId.substring(0, 12));
    }

    public void removeContainer(String containerId) {
        dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        log.info("Removed container {}", containerId.substring(0, 12));
    }

    /** Force-stop + remove with {@code withRemoveVolumes(true)} so anonymous data volumes vanish.
     *  Used by the "Hard reset" flow that wants a clean slate on every redeploy. */
    public void hardRemoveContainer(String containerId) {
        try {
            dockerClient.removeContainerCmd(containerId)
                .withForce(true)
                .withRemoveVolumes(true)
                .exec();
            log.info("Hard-removed container {} (with volumes)", containerId.substring(0, 12));
        } catch (Exception e) {
            log.warn("Hard remove failed for {}: {}", containerId, e.getMessage());
        }
    }

    /**
     * Find and force-remove every container (running or stopped) whose name starts with
     * {@code namePrefix}, dropping their anonymous data volumes too. Used as a defensive sweep
     * before creating a fresh container so stale orphans from a previous run (e.g. a hard reset
     * that didn't clean up because the DB row had a null containerId) can't keep old data alive.
     */
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
                    log.info("Removed orphan container {} ({})", matched, c.getId().substring(0, 12));
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
                .awaitCompletion(10, TimeUnit.SECONDS);
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
            .withTail(50)
            .exec(new ResultCallback.Adapter<Frame>() {
                @Override
                public void onNext(Frame frame) {
                    try {
                        emitter.send(SseEmitter.event()
                            .name("log")
                            .data(new String(frame.getPayload())));
                    } catch (IOException e) {
                        try { close(); } catch (IOException ex) { /* ignore */ }
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

    public boolean isContainerRunning(String containerId) {
        try {
            var info = dockerClient.inspectContainerCmd(containerId).exec();
            return Boolean.TRUE.equals(info.getState().getRunning());
        } catch (Exception e) {
            return false;
        }
    }

    public int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("No available port found", e);
        }
    }

    /** Execute a command inside a running container and return stdout */
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
                .awaitCompletion(120, TimeUnit.SECONDS);
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
            var inputStream = new java.io.ByteArrayInputStream(stdin.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            dockerClient.execStartCmd(execCreate.getId())
                .withStdIn(inputStream)
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        sb.append(new String(frame.getPayload()));
                    }
                })
                .awaitCompletion(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return sb.toString();
    }
}
