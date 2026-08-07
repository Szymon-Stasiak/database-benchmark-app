package com.dbagnets.backend.engine.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EntityIdRegistry {

    private final EntityIdRegistryRepository repository;

    @Transactional
    public void recordAll(
            String benchmarkId, String databaseId, String entityName, List<RegistryEntry> entries) {
        if (entries.isEmpty()) return;
        List<EntityIdRecord> records =
                entries.stream()
                        .map(
                                e ->
                                        new EntityIdRecord(
                                                benchmarkId,
                                                databaseId,
                                                entityName,
                                                e.logicalId(),
                                                e.physicalId()))
                        .toList();
        repository.saveAll(records);
    }

    @Transactional(readOnly = true)
    public long countLogicalIds(String benchmarkId, String entityName) {
        return repository.countByBenchmarkIdAndEntityName(benchmarkId, entityName);
    }

    @Transactional(readOnly = true)
    public List<String> selectLogicalIds(
            String benchmarkId, String entityName, int sampleSize, SelectionStrategy strategy) {
        List<String> all = new ArrayList<>(repository.distinctLogicalIds(benchmarkId, entityName));
        if (all.isEmpty()) return List.of();
        return switch (strategy) {
            case RANDOM_UNIFORM -> sampleRandom(all, sampleSize);
        };
    }

    @Transactional(readOnly = true)
    public List<RegistryEntry> lookupEntries(
            String databaseId, String entityName, List<String> logicalIds) {
        if (logicalIds.isEmpty()) return List.of();
        List<EntityIdRecord> records =
                repository.findByDatabaseAndEntityAndLogicalIds(databaseId, entityName, logicalIds);
        Map<String, String> physicalByLogical = new HashMap<>(records.size());
        for (EntityIdRecord r : records) {
            physicalByLogical.put(r.getLogicalId(), r.getPhysicalId());
        }
        List<RegistryEntry> ordered = new ArrayList<>(logicalIds.size());
        for (String logicalId : logicalIds) {
            String physicalId = physicalByLogical.get(logicalId);
            if (physicalId != null) {
                ordered.add(new RegistryEntry(logicalId, physicalId));
            }
        }
        return ordered;
    }

    @Transactional(readOnly = true)
    public List<String> sampleLogicalIds(String benchmarkId, String entityName, int sampleSize) {
        return selectLogicalIds(
                benchmarkId, entityName, sampleSize, SelectionStrategy.RANDOM_UNIFORM);
    }

    @Transactional
    public void deleteByLogicalIds(String databaseId, String entityName, List<String> logicalIds) {
        if (logicalIds.isEmpty()) return;
        repository.deleteByDatabaseIdAndEntityNameAndLogicalIdIn(
                databaseId, entityName, logicalIds);
    }

    @Transactional
    public void deleteByPhysicalIds(
            String databaseId, String entityName, List<String> physicalIds) {
        if (physicalIds.isEmpty()) return;
        repository.deleteByDatabaseIdAndEntityNameAndPhysicalIdIn(
                databaseId, entityName, physicalIds);
    }

    @Transactional
    public void evictAllForDatabase(String databaseId) {
        repository.deleteByDatabaseId(databaseId);
    }

    @Transactional
    public void evictAllForBenchmark(String benchmarkId) {
        repository.deleteByBenchmarkId(benchmarkId);
    }

    private List<String> sampleRandom(List<String> all, int sampleSize) {
        Collections.shuffle(all, ThreadLocalRandom.current());
        if (all.size() <= sampleSize) return all;
        return new ArrayList<>(all.subList(0, sampleSize));
    }

    public record RegistryEntry(String logicalId, String physicalId) {}
}
