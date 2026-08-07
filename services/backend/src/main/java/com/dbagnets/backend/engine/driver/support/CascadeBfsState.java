package com.dbagnets.backend.engine.driver.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CascadeBfsState {

    private final Map<String, List<Object>> byEntity = new LinkedHashMap<>();
    private final Set<String> visited = new HashSet<>();
    private final Deque<String> queue = new ArrayDeque<>();
    private final int maxDepth;
    private int safety = 0;

    public CascadeBfsState(String rootEntity, Object rootId, int maxDepth) {
        byEntity.put(rootEntity, new ArrayList<>(List.of(rootId)));
        queue.add(rootEntity);
        this.maxDepth = maxDepth;
    }

    public boolean hasNext() {
        return !queue.isEmpty() && safety++ < maxDepth;
    }

    public String poll() {
        return queue.poll();
    }

    public boolean isNotVisited(String entity) {
        return !visited.add(entity);
    }

    public List<Object> idsFor(String entity) {
        return byEntity.get(entity);
    }

    public void addChildren(String childEntity, List<Object> childIds) {
        byEntity.computeIfAbsent(childEntity, k -> new ArrayList<>()).addAll(childIds);
        if (!visited.contains(childEntity)) queue.add(childEntity);
    }

    public List<String> reversedEntityOrder() {
        List<String> order = new ArrayList<>(byEntity.keySet());
        Collections.reverse(order);
        return order;
    }

    public Map<String, List<Object>> snapshot() {
        return new LinkedHashMap<>(byEntity);
    }
}
