package com.dbagnets.backend.benchmark.registry;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class EntityIdRegistry {

    private final EntityIdRegistryRepository repository;

    @Transactional
    public void recordAll(String benchmarkId,
                          String databaseId,
                          String entityName,
                          List<RegistryEntry> entries) {
        if (entries.isEmpty()) return;
        List<EntityIdRecord> records = entries.stream()
                .map(e -> new EntityIdRecord(benchmarkId, databaseId, entityName, e.logicalId(), e.physicalId()))
                .toList();
        repository.saveAll(records);
    }

    @Transactional(readOnly = true)
    public List<String> sampleLogicalIds(String benchmarkId, String entityName, int sampleSize) {
        List<String> all = new ArrayList<>(repository.distinctLogicalIds(benchmarkId, entityName));
        if (all.isEmpty()) return List.of();
        if (all.size() <= sampleSize) {
            Collections.shuffle(all, ThreadLocalRandom.current());
            return all;
        }
        Collections.shuffle(all, ThreadLocalRandom.current());
        return new ArrayList<>(all.subList(0, sampleSize));
    }

    @Transactional(readOnly = true)
    public List<RegistryEntry> sampleEntries(String databaseId, String entityName, int sampleSize) {
        List<EntityIdRecord> all = new ArrayList<>(repository.findByDatabaseIdAndEntityName(databaseId, entityName));
        if (all.isEmpty()) return List.of();
        Collections.shuffle(all, ThreadLocalRandom.current());
        List<EntityIdRecord> picked = all.size() <= sampleSize ? all : all.subList(0, sampleSize);
        return picked.stream()
                .map(r -> new RegistryEntry(r.getLogicalId(), r.getPhysicalId()))
                .toList();
    }

    @Transactional
    public int deleteByLogicalIds(String databaseId, String entityName, List<String> logicalIds) {
        if (logicalIds.isEmpty()) return 0;
        return repository.deleteByDatabaseIdAndEntityNameAndLogicalIdIn(databaseId, entityName, logicalIds);
    }

    public record RegistryEntry(String logicalId, String physicalId) {
    }
}
