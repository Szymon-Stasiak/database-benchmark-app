package com.dbagnets.backend.insert.model;

public record AttributeChoice(
    String name,
    String dataType,
    String description,
    boolean primaryKey,
    boolean nullable
) {}
