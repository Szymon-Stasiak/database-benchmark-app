package com.dbagnets.backend.benchmark.run.application.scenario;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;

import com.dbagnets.backend.domain.DatabaseEngine;
import com.dbagnets.backend.engine.scenario.ScenarioApplicability;
import com.dbagnets.backend.engine.scenario.ScenarioType;
import com.dbagnets.backend.infrastructure.persistence.BenchmarkRepository;
import com.dbagnets.backend.shared.entity.Benchmark;
import com.dbagnets.backend.shared.entity.BenchmarkDatabase;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ScenarioApplicabilityService {

    private final BenchmarkRepository benchmarkRepository;

    public Map<String, List<String>> applicableDatabaseIdsByScenario(String benchmarkId) {
        Benchmark benchmark =
                benchmarkRepository
                        .findById(benchmarkId)
                        .orElseThrow(
                                () ->
                                        new NoSuchElementException(
                                                "Benchmark not found: " + benchmarkId));
        Map<String, List<String>> result = new HashMap<>();
        for (ScenarioType type : ScenarioType.values()) {
            List<String> applicableDbIds = new ArrayList<>();
            for (BenchmarkDatabase db : benchmark.getDatabases()) {
                if (isApplicable(type, db)) {
                    applicableDbIds.add(db.getId());
                }
            }
            result.put(type.name(), applicableDbIds);
        }
        return result;
    }

    private boolean isApplicable(ScenarioType type, BenchmarkDatabase db) {
        try {
            DatabaseEngine engine = DatabaseEngine.of(db.getDbName());
            return ScenarioApplicability.isApplicable(type, engine);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
