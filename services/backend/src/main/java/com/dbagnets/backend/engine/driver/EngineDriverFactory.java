package com.dbagnets.backend.engine.driver;

import com.dbagnets.backend.domain.DatabaseEngine;
import com.dbagnets.backend.domain.DatabaseStatus;
import com.dbagnets.backend.shared.entity.BenchmarkDatabase;
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

    public DriverResolution resolve(BenchmarkDatabase db) {
        DatabaseEngine engine;
        try {
            engine = DatabaseEngine.of(db.getDbName());
        } catch (IllegalArgumentException e) {
            return new DriverResolution.Skipped("Unknown engine: " + db.getDbName());
        }
        if (!supports(engine) || db.getStatus() != DatabaseStatus.RUNNING || db.getHostPort() == null) {
            return new DriverResolution.Skipped("Engine not supported or container not running");
        }
        return new DriverResolution.Resolved(driverFor(engine).orElseThrow());
    }
}