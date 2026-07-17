package com.dbagnets.backend.shared.event;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface BenchmarkEventPort {

    SseEmitter subscribe(String benchmarkId);

    void sendEvent(String benchmarkId, String eventType, Object data);

    void broadcastBenchmarkStatus(String benchmarkId, Object status);

    void broadcastDatabaseStatus(String benchmarkId, String databaseId, Object status);

    void broadcastDatabaseStatus(String benchmarkId, String databaseId, Object status, String errorMessage);

    void broadcastDatabasePortAssigned(String benchmarkId, String databaseId, int hostPort);
}
