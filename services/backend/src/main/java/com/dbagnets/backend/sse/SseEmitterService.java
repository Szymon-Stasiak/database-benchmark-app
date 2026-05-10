package com.dbagnets.backend.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class SseEmitterService {
    private static final Logger log = LoggerFactory.getLogger(SseEmitterService.class);
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public SseEmitterService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

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

        var deadEmitters = new ArrayList<SseEmitter>();
        for (var emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                    .name(eventType)
                    .data(objectMapper.writeValueAsString(data)));
            } catch (IOException e) {
                deadEmitters.add(emitter);
            }
        }
        list.removeAll(deadEmitters);
    }

    @Scheduled(fixedRate = 30000)
    public void sendHeartbeats() {
        emitters.forEach((benchmarkId, list) -> {
            var deadEmitters = new ArrayList<SseEmitter>();
            for (var emitter : list) {
                try {
                    emitter.send(SseEmitter.event().name("heartbeat").data("{}"));
                } catch (IOException e) {
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
