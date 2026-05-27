package com.dbagnets.backend.insert.cascade;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe registry of primary-key values per entity.
 *
 * <p>Populated by the orchestrator as each parent entity finishes inserting its records.
 * Children then call {@link #randomFk(String)} to fill their FK columns with a real parent PK,
 * keeping referential integrity intact across the cascade.
 */
public final class PrimaryKeyRegistry {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Object>> byEntity = new ConcurrentHashMap<>();
    private final Random random;

    public PrimaryKeyRegistry() {
        this(new Random());
    }

    public PrimaryKeyRegistry(Random random) {
        this.random = random;
    }

    public void record(String entityName, List<Object> pks) {
        byEntity.computeIfAbsent(normalize(entityName), k -> new CopyOnWriteArrayList<>()).addAll(pks);
    }

    public Object randomFk(String entityName) {
        var list = byEntity.get(normalize(entityName));
        if (list == null || list.isEmpty()) return null;
        return list.get(random.nextInt(list.size()));
    }

    public int size(String entityName) {
        var list = byEntity.get(normalize(entityName));
        return list == null ? 0 : list.size();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
