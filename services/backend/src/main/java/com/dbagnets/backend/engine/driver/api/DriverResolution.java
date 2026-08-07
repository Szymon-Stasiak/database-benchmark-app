package com.dbagnets.backend.engine.driver.api;

public sealed interface DriverResolution {

    record Resolved(EngineDriver driver) implements DriverResolution {}

    record Skipped(String reason) implements DriverResolution {}
}