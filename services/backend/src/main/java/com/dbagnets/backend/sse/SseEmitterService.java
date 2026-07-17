package com.dbagnets.backend.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@RequiredArgsConstructor
@Slf4j
public class SseEmitterService {
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public SseEmitter subscribe(String benchmarkId) {
        var emitter = new SseEmitter(0L);
        emitters.computeIfAbsent(benchmarkId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(benchmarkId, emitter));
        emitter.onTimeout(() -> removeEmitter(benchmarkId, emitter));
        emitter.onError(e -> removeEmitter(benchmarkId, emitter));
        log.debug("SSE subscriber added for benchmark {}", benchmarkId);
        return emitter;
    }

    public void sendEvent(String benchmarkId, String eventType, Object data) {
        var list = emitters.get(benchmarkId);
        if (list == null || list.isEmpty()) return;

        String payload;
        try {
            payload = objectMapper.writeValueAsString(data);
        } catch (IOException e) {
            log.warn("Failed to serialize SSE payload for benchmark {} event {}: {}",
                    benchmarkId, eventType, e.getMessage());
            return;
        }

        var deadEmitters = new ArrayList<SseEmitter>();
        for (var emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(eventType).data(payload));
            } catch (IOException | IllegalStateException e) {
                deadEmitters.add(emitter);
            } catch (Exception e) {
                log.debug("SSE send failed for benchmark {}: {}", benchmarkId, e.getMessage());
                deadEmitters.add(emitter);
            }
        }
        if (!deadEmitters.isEmpty()) {
            list.removeAll(deadEmitters);
            if (list.isEmpty()) emitters.remove(benchmarkId);
        }
    }

    public void broadcastBenchmarkStatus(String benchmarkId, Object status) {
        sendEvent(benchmarkId, SseEvents.EVENT_BENCHMARK_STATUS,
            SseEvents.benchmarkStatusPayload(benchmarkId, status));
    }

    public void broadcastDatabaseStatus(String benchmarkId, String databaseId, Object status) {
        broadcastDatabaseStatus(benchmarkId, databaseId, status, null);
    }

    public void broadcastDatabaseStatus(String benchmarkId, String databaseId, Object status, String errorMessage) {
        sendEvent(benchmarkId, SseEvents.EVENT_DATABASE_STATUS,
            SseEvents.databaseStatusPayload(benchmarkId, databaseId, status, errorMessage));
    }

    public void broadcastDatabasePortAssigned(String benchmarkId, String databaseId, int hostPort) {
        sendEvent(benchmarkId, SseEvents.EVENT_DATABASE_PORT_ASSIGNED,
            SseEvents.databasePortAssignedPayload(benchmarkId, databaseId, hostPort));
    }

    @Scheduled(fixedRate = 30000)
    public void sendHeartbeats() {
        emitters.forEach((benchmarkId, list) -> {
            var deadEmitters = new ArrayList<SseEmitter>();
            for (var emitter : list) {
                try {
                    emitter.send(SseEmitter.event().name(SseEvents.EVENT_HEARTBEAT).data("{}"));
                } catch (IOException | IllegalStateException e) {
                    deadEmitters.add(emitter);
                } catch (Exception e) {
                    deadEmitters.add(emitter);
                }
            }
            list.removeAll(deadEmitters);
            if (list.isEmpty()) emitters.remove(benchmarkId);
        });
    }

    private void removeEmitter(String benchmarkId, SseEmitter emitter) {
        var list = emitters.get(benchmarkId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) emitters.remove(benchmarkId);
        }
    }
}