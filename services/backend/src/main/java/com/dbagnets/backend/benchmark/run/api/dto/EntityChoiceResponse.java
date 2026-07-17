package com.dbagnets.backend.benchmark.run.api.dto;

import java.util.List;

public record EntityChoiceResponse(
        String name,
        String description,
        List<AttributeChoice> attributes
) {
    public record AttributeChoice(
            String name,
            String dataType,
            String description,
            boolean primaryKey,
            boolean nullable
    ) {
    }
}
