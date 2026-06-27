package com.dbagnets.backend.benchmark.resource;

import com.dbagnets.backend.docker.DockerService;
import com.dbagnets.backend.sse.SseEmitterService;
import com.dbagnets.backend.sse.SseEvents;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Statistics;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContainerStatsCollector {

    private static final long SAMPLE_INTERVAL_MS = 250L;
    private static final int CONSECUTIVE_FAILURE_LIMIT = 3;

    private final DockerService dockerService;
    private final SseEmitterService sse;
    private final ObjectMapper objectMapper;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(4, Thread.ofVirtual().factory());
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public Handle start(String benchmarkId,
                        String runId,
                        String resultId,
                        String databaseId,
                        String dbName,
                        String containerId,
                        String operation) {
        if (containerId == null || containerId.isBlank()) {
            return Handle.disabled();
        }
        Session session = new Session(benchmarkId, runId, resultId, databaseId, dbName, containerId, operation);
        sessions.put(resultId, session);
        tickSync(session);
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(
                () -> tick(session),
                SAMPLE_INTERVAL_MS,
                SAMPLE_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
        session.task.set(task);
        return new Handle(resultId);
    }

    public ResourceMetricsSummary stop(Handle handle) {
        if (handle == null || handle.resultId == null) {
            return ResourceMetricsSummary.empty();
        }
        Session session = sessions.remove(handle.resultId);
        if (session == null) {
            return ResourceMetricsSummary.empty();
        }
        ScheduledFuture<?> task = session.task.get();
        if (task != null) task.cancel(false);
        tickSync(session);
        return summarize(session.samples);
    }

    private void tickSync(Session session) {
        try {
            tick(session);
        } catch (Exception e) {
            log.debug("Synchronous stats sample failed for container {}: {}", session.containerId, e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private void tick(Session session) {
        if (session.failureStreak.get() >= CONSECUTIVE_FAILURE_LIMIT) {
            ScheduledFuture<?> task = session.task.get();
            if (task != null) task.cancel(false);
            return;
        }
        try {
            Statistics stats = fetchSingleStat(session.containerId);
            if (stats == null) {
                session.failureStreak.incrementAndGet();
                return;
            }
            session.failureStreak.set(0);
            ResourceSample sample = toSample(stats, session.lastStats.get());
            session.lastStats.set(stats);
            if (sample == null) return;
            session.samples.add(sample);
            broadcast(session, sample);
        } catch (Exception e) {
            log.debug("Stats tick failed for container {}: {}", session.containerId, e.getMessage());
            session.failureStreak.incrementAndGet();
        }
    }

    private Statistics fetchSingleStat(String containerId) throws InterruptedException {
        AtomicReference<Statistics> result = new AtomicReference<>();
        var callback = new ResultCallback.Adapter<Statistics>() {
            @Override
            public void onNext(Statistics s) {
                if (result.get() == null) result.set(s);
                try { close(); } catch (Exception ignored) {}
            }
        };
        dockerService.getClient()
                .statsCmd(containerId)
                .withNoStream(true)
                .exec(callback)
                .awaitCompletion(2, TimeUnit.SECONDS);
        return result.get();
    }

    private ResourceSample toSample(Statistics current, Statistics previous) {
        long timestamp = System.currentTimeMillis();
        double cpuPercent = computeCpuPercent(current, previous);
        long memoryBytes = computeMemoryBytes(current);
        long memoryLimit = computeMemoryLimit(current);
        return new ResourceSample(timestamp, cpuPercent, memoryBytes, memoryLimit);
    }

    private double computeCpuPercent(Statistics current, Statistics previous) {
        if (current.getCpuStats() == null || current.getCpuStats().getCpuUsage() == null) return 0.0;
        Long totalUsage = current.getCpuStats().getCpuUsage().getTotalUsage();
        Long systemUsage = current.getCpuStats().getSystemCpuUsage();
        if (totalUsage == null || systemUsage == null) return 0.0;

        Long prevTotal = null;
        Long prevSystem = null;
        if (previous != null && previous.getCpuStats() != null && previous.getCpuStats().getCpuUsage() != null) {
            prevTotal = previous.getCpuStats().getCpuUsage().getTotalUsage();
            prevSystem = previous.getCpuStats().getSystemCpuUsage();
        }
        if (prevTotal == null || prevSystem == null) return 0.0;

        long cpuDelta = totalUsage - prevTotal;
        long systemDelta = systemUsage - prevSystem;
        if (cpuDelta <= 0 || systemDelta <= 0) return 0.0;

        long onlineCpus = resolveOnlineCpus(current);
        if (onlineCpus <= 0) onlineCpus = 1;
        return ((double) cpuDelta / (double) systemDelta) * onlineCpus * 100.0;
    }

    private long resolveOnlineCpus(Statistics stats) {
        if (stats.getCpuStats() == null) return 1;
        Long online = stats.getCpuStats().getOnlineCpus();
        if (online != null && online > 0) return online;
        if (stats.getCpuStats().getCpuUsage() != null
                && stats.getCpuStats().getCpuUsage().getPercpuUsage() != null) {
            return stats.getCpuStats().getCpuUsage().getPercpuUsage().size();
        }
        return 1;
    }

    private long computeMemoryBytes(Statistics stats) {
        if (stats.getMemoryStats() == null || stats.getMemoryStats().getUsage() == null) return 0L;
        long usage = stats.getMemoryStats().getUsage();
        long cache = 0L;
        Map<String, Object> raw = stats.getMemoryStats().getStats() == null
                ? Collections.emptyMap()
                : objectMapper.convertValue(stats.getMemoryStats().getStats(), Map.class);
        Object cacheValue = raw.getOrDefault("cache", raw.get("inactive_file"));
        if (cacheValue instanceof Number n) cache = n.longValue();
        return Math.max(0L, usage - cache);
    }

    private long computeMemoryLimit(Statistics stats) {
        if (stats.getMemoryStats() == null || stats.getMemoryStats().getLimit() == null) return 0L;
        return stats.getMemoryStats().getLimit();
    }

    private void broadcast(Session session, ResourceSample sample) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("runId", session.runId);
        payload.put("resultId", session.resultId);
        payload.put("databaseId", session.databaseId);
        payload.put("dbName", session.dbName);
        payload.put("operation", session.operation);
        payload.put("timestamp", sample.tMs());
        payload.put("cpuPercent", sample.cpuPercent());
        payload.put("memoryBytes", sample.memoryBytes());
        payload.put("memoryLimitBytes", sample.memoryLimitBytes());
        sse.sendEvent(session.benchmarkId, SseEvents.EVENT_CONTAINER_STATS, payload);
    }

    private ResourceMetricsSummary summarize(List<ResourceSample> samples) {
        if (samples.isEmpty()) return ResourceMetricsSummary.empty();
        List<ResourceSample> snapshot = new ArrayList<>(samples);
        double[] cpu = snapshot.stream().mapToDouble(ResourceSample::cpuPercent).toArray();
        long[] mem = snapshot.stream().mapToLong(ResourceSample::memoryBytes).toArray();

        double cpuMax = Arrays.stream(cpu).max().orElse(0.0);
        double cpuMean = Arrays.stream(cpu).average().orElse(0.0);
        double cpuP95 = percentileDouble(cpu, 95);

        long memMax = Arrays.stream(mem).max().orElse(0L);
        double memMeanDouble = Arrays.stream(mem).average().orElse(0.0);
        long memMean = (long) memMeanDouble;
        long memP95 = percentileLong(mem, 95);

        return new ResourceMetricsSummary(
                cpuMax,
                cpuMean,
                cpuP95,
                memMax,
                memMean,
                memP95,
                snapshot.size(),
                serializeSamples(snapshot));
    }

    private double percentileDouble(double[] values, int p) {
        if (values.length == 0) return 0.0;
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int idx = Math.max(0, (int) Math.ceil((p / 100.0) * sorted.length) - 1);
        return sorted[Math.min(idx, sorted.length - 1)];
    }

    private long percentileLong(long[] values, int p) {
        if (values.length == 0) return 0L;
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int idx = Math.max(0, (int) Math.ceil((p / 100.0) * sorted.length) - 1);
        return sorted[Math.min(idx, sorted.length - 1)];
    }

    private String serializeSamples(List<ResourceSample> samples) {
        try {
            return objectMapper.writeValueAsString(samples);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize resource samples: {}", e.getMessage());
            return null;
        }
    }

    public static final class Handle {
        private final String resultId;

        private Handle(String resultId) {
            this.resultId = resultId;
        }

        static Handle disabled() {
            return new Handle(null);
        }
    }

    private static final class Session {
        final String benchmarkId;
        final String runId;
        final String resultId;
        final String databaseId;
        final String dbName;
        final String containerId;
        final String operation;
        final List<ResourceSample> samples = Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<Statistics> lastStats = new AtomicReference<>();
        final AtomicReference<ScheduledFuture<?>> task = new AtomicReference<>();
        final AtomicInteger failureStreak = new AtomicInteger(0);

        Session(String benchmarkId, String runId, String resultId, String databaseId,
                String dbName, String containerId, String operation) {
            this.benchmarkId = benchmarkId;
            this.runId = runId;
            this.resultId = resultId;
            this.databaseId = databaseId;
            this.dbName = dbName;
            this.containerId = containerId;
            this.operation = operation;
        }
    }
}
