package com.dbagnets.backend.infrastructure.scriptgen;

import java.util.List;

public record ScriptCreatorRequest(
    String idea,
    int depth,
    List<TargetRequest> targets,
    String model,
    int max_iterations,
    boolean sequential
) {
    public record TargetRequest(
        String db_type,
        String db_name,
        String db_version
    ) {}
}