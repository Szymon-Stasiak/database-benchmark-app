package com.dbagnets.backend.benchmark.scenario;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = AggregateParams.class, name = "AGGREGATE_GROUP_COUNT"),
        @JsonSubTypes.Type(value = RangeParams.class, name = "RANGE_FILTER"),
        @JsonSubTypes.Type(value = TraversalParams.class, name = "GRAPH_TRAVERSAL"),
        @JsonSubTypes.Type(value = KnnParams.class, name = "VECTOR_KNN")
})
public sealed interface ScenarioParams
        permits AggregateParams, RangeParams, TraversalParams, KnnParams {

    ScenarioType type();
}
