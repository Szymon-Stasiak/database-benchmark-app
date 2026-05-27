package com.dbagnets.backend.insert.model;

import com.dbagnets.backend.insert.entity.InsertMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Multi-entity cascade insert request.
 *
 * <p>The user picks one or more leaf {@link EntityCascadeChoice}s. Each choice carries its
 * desired record count and optional per-edge ratio overrides; the backend's {@code CascadeResolver}
 * expands every choice into the full topological list of entities required to satisfy FK
 * constraints and propagates record counts up the parent chain.
 *
 * <p>{@code workerCount} is the per-DB count of Java Virtual Threads (each holding one connection
 * from a pool) that consume the per-batch queue during execution.
 */
public record StartInsertRunRequest(
    @NotEmpty(message = "Pick at least one entity to insert into")
    @Valid List<EntityCascadeChoice> entities,

    @NotNull InsertMode mode,

    @Min(value = 1, message = "batchSize must be >= 1 when provided")
    Integer batchSize,

    @Min(value = 1, message = "workerCount must be between 1 and 64")
    @Max(value = 64, message = "workerCount must be between 1 and 64")
    Integer workerCount,

    @NotEmpty(message = "Select at least one database to run the insert against")
    List<String> databaseIds
) {
    public int effectiveWorkerCount() {
        return workerCount == null ? 1 : workerCount;
    }
}
