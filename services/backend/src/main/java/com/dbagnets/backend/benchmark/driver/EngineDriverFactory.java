package com.dbagnets.backend.benchmark.driver;

import com.dbagnets.backend.entity.DatabaseEngine;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class EngineDriverFactory {

    private final Map<DatabaseEngine, EngineDriver> drivers;

    public EngineDriverFactory(List<EngineDriver> all) {
        EnumMap<DatabaseEngine, EngineDriver> map = new EnumMap<>(DatabaseEngine.class);
        for (EngineDriver d : all) {
            map.put(d.engine(), d);
        }
        this.drivers = map;
    }

    public Optional<EngineDriver> driverFor(DatabaseEngine engine) {
        return Optional.ofNullable(drivers.get(engine));
    }

    public boolean supports(DatabaseEngine engine) {
        return drivers.containsKey(engine);
    }
}
