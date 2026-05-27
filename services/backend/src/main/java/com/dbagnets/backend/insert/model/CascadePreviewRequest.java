package com.dbagnets.backend.insert.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Request body for {@code POST /cascade-preview}: leaf entity choices and any edge-ratio overrides
 * the UI already collected. The endpoint runs {@code CascadeResolver} and returns the resolved
 * topological plan so the picker can show "→ N records" labels in real time.
 */
public record CascadePreviewRequest(
    @NotEmpty @Valid List<EntityCascadeChoice> entities
) {}
