package com.dbagnets.backend.benchmark.run.api.dto;

import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalEntity;

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
        public static AttributeChoice from(LogicalAttribute a) {
            return new AttributeChoice(a.name(), a.dataType().name(), a.description(), a.isPrimaryKey(), a.isNullable());
        }
    }

    public static EntityChoiceResponse from(LogicalEntity entity) {
        List<AttributeChoice> attrs = entity.attributes().stream().map(AttributeChoice::from).toList();
        return new EntityChoiceResponse(entity.name(), entity.description(), attrs);
    }
}