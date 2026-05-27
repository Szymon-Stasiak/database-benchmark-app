package com.dbagnets.backend.insert.datagen;

import com.dbagnets.backend.insert.schema.LogicalAttribute;
import com.dbagnets.backend.insert.schema.LogicalEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RecordGenerator {

    private final DataFakerService dataFakerService;

    public RecordGenerator(DataFakerService dataFakerService) {
        this.dataFakerService = dataFakerService;
    }

    public List<GeneratedRecord> generate(LogicalEntity entity, int count) {
        if (count <= 0) return Collections.emptyList();
        List<LogicalAttribute> attributes = entity.attributesOrEmpty();
        if (attributes.isEmpty()) {
            throw new IllegalArgumentException("Entity " + entity.name() + " has no attributes — cannot generate records");
        }
        List<GeneratedRecord> records = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Map<String, Object> values = new LinkedHashMap<>(attributes.size());
            for (LogicalAttribute attr : attributes) {
                values.put(attr.name(), dataFakerService.generate(attr));
            }
            records.add(new GeneratedRecord(values));
        }
        return Collections.unmodifiableList(records);
    }
}
