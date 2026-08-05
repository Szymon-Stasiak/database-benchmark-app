package com.dbagnets.backend.engine.driver;

import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalDataType;
import com.dbagnets.backend.engine.schema.LogicalEntity;

import java.util.ArrayList;
import java.util.List;

public final class VectorAttributes {

    private VectorAttributes() {}

    public static LogicalAttribute find(LogicalEntity entity) {
        return entity.attributes().stream()
                .filter(a -> a.dataType() == LogicalDataType.VECTOR)
                .findFirst()
                .orElse(null);
    }

    public static List<Float> toList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) list.add(f);
        return list;
    }
}
