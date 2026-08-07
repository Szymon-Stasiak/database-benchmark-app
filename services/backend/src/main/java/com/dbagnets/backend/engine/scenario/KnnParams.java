package com.dbagnets.backend.engine.scenario;

import java.util.Arrays;

public record KnnParams(String entityName, String vectorAttribute, double[] queryVector, int topK)
        implements ScenarioParams {

    public static final int MAX_TOP_K = 1000;

    public KnnParams {
        if (entityName == null || entityName.isBlank()) {
            throw new IllegalArgumentException("entityName is required for VECTOR_KNN");
        }
        if (vectorAttribute == null || vectorAttribute.isBlank()) {
            throw new IllegalArgumentException("vectorAttribute is required for VECTOR_KNN");
        }
        if (queryVector == null || queryVector.length == 0) {
            throw new IllegalArgumentException("queryVector must be non-empty");
        }
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new IllegalArgumentException("topK must be between 1 and " + MAX_TOP_K);
        }
        queryVector = queryVector.clone();
    }

    @Override
    public double[] queryVector() {
        return queryVector.clone();
    }

    @Override
    public ScenarioType type() {
        return ScenarioType.VECTOR_KNN;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof KnnParams other)) return false;
        return topK == other.topK
                && entityName.equals(other.entityName)
                && vectorAttribute.equals(other.vectorAttribute)
                && Arrays.equals(queryVector, other.queryVector);
    }

    @Override
    public int hashCode() {
        int result = entityName.hashCode();
        result = 31 * result + vectorAttribute.hashCode();
        result = 31 * result + Arrays.hashCode(queryVector);
        result = 31 * result + topK;
        return result;
    }
}
