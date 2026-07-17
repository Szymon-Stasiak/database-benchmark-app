package com.dbagnets.backend.engine.scenario;

import com.dbagnets.backend.domain.DatabaseEngine;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioApplicabilityTest {

    @Test
    void aggregateCoversRelationalDocumentAndGraph() {
        Set<DatabaseEngine> applicable = ScenarioApplicability.applicableEngines(ScenarioType.AGGREGATE_GROUP_COUNT);
        assertThat(applicable).contains(DatabaseEngine.POSTGRESQL, DatabaseEngine.MONGODB, DatabaseEngine.NEO4J);
    }

    @Test
    void rangeFilterCoversRelationalDocumentAndGraph() {
        Set<DatabaseEngine> applicable = ScenarioApplicability.applicableEngines(ScenarioType.RANGE_FILTER);
        assertThat(applicable).contains(DatabaseEngine.POSTGRESQL, DatabaseEngine.MONGODB, DatabaseEngine.NEO4J);
    }

    @Test
    void traversalCoversRelationalDocumentAndGraph() {
        Set<DatabaseEngine> applicable = ScenarioApplicability.applicableEngines(ScenarioType.GRAPH_TRAVERSAL);
        assertThat(applicable).contains(DatabaseEngine.POSTGRESQL, DatabaseEngine.MONGODB, DatabaseEngine.NEO4J);
    }

    @Test
    void knnRestrictedToVectorEngines() {
        Set<DatabaseEngine> applicable = ScenarioApplicability.applicableEngines(ScenarioType.VECTOR_KNN);
        assertThat(applicable).containsExactly(DatabaseEngine.QDRANT);
    }

    @Test
    void redisAndDynamoSkippedForAllStructuredScenarios() {
        for (ScenarioType type : new ScenarioType[]{
                ScenarioType.AGGREGATE_GROUP_COUNT,
                ScenarioType.RANGE_FILTER,
                ScenarioType.GRAPH_TRAVERSAL,
                ScenarioType.VECTOR_KNN
        }) {
            assertThat(ScenarioApplicability.isApplicable(type, DatabaseEngine.REDIS))
                    .as("REDIS should not support %s", type)
                    .isFalse();
            assertThat(ScenarioApplicability.isApplicable(type, DatabaseEngine.DYNAMODB))
                    .as("DYNAMODB should not support %s", type)
                    .isFalse();
        }
    }

    @Test
    void postgresqlDoesNotAdvertiseKnn() {
        assertThat(ScenarioApplicability.isApplicable(ScenarioType.VECTOR_KNN, DatabaseEngine.POSTGRESQL))
                .isFalse();
    }
}
