package com.dbagnets.backend.benchmark.run.application.insert;

import com.dbagnets.backend.benchmark.run.api.dto.BatchProgressEvent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InsertProgressTracker {

    private final Map<String, Map<String, BatchProgressEvent>> byRun = new ConcurrentHashMap<>();

    public void record(BatchProgressEvent event) {
        if (event == null || event.runId() == null) return;
        String key = key(event.databaseId(), event.entityName());
        byRun.computeIfAbsent(event.runId(), k -> new ConcurrentHashMap<>()).put(key, event);
    }

    public List<BatchProgressEvent> snapshot(String runId) {
        Map<String, BatchProgressEvent> per = byRun.get(runId);
        if (per == null) return List.of();
        return new ArrayList<>(per.values());
    }

    private String key(String databaseId, String entityName) {
        return databaseId + "::" + (entityName == null ? "" : entityName.toLowerCase());
    }
}