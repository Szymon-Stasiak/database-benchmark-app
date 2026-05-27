package com.dbagnets.backend.insert.model;

import java.util.List;

public record EntityChoice(
    String name,
    String description,
    List<AttributeChoice> attributes
) {}
