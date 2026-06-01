package com.dbagnets.backend.benchmark.datagen;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

public final class PrimaryKeyVault {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<String>> pksByEntity = new ConcurrentHashMap<>();

    public void append(String entityName, String pk) {
        pksByEntity.computeIfAbsent(entityName, k -> new CopyOnWriteArrayList<>()).add(pk);
    }

    public List<String> snapshot(String entityName) {
        return List.copyOf(pksByEntity.getOrDefault(entityName, new CopyOnWriteArrayList<>()));
    }

    public String randomPk(String entityName) {
        CopyOnWriteArrayList<String> pks = pksByEntity.get(entityName);
        if (pks == null || pks.isEmpty()) {
            throw new IllegalStateException(
                    "No primary keys available for parent entity '" + entityName +
                            "'. CascadePlanner ordering must ensure parents are generated before children.");
        }
        return pks.get(ThreadLocalRandom.current().nextInt(pks.size()));
    }

    public int size(String entityName) {
        CopyOnWriteArrayList<String> pks = pksByEntity.get(entityName);
        return pks == null ? 0 : pks.size();
    }

    public void clear() {
        pksByEntity.clear();
    }
}
