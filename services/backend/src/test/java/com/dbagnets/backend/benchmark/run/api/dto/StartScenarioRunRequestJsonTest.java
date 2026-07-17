package com.dbagnets.backend.benchmark.run.api.dto;

import com.dbagnets.backend.engine.scenario.AggregateParams;
import com.dbagnets.backend.engine.scenario.KnnParams;
import com.dbagnets.backend.engine.scenario.RangeParams;
import com.dbagnets.backend.engine.scenario.ScenarioParams;
import com.dbagnets.backend.engine.scenario.ScenarioType;
import com.dbagnets.backend.engine.scenario.TraversalParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StartScenarioRunRequestJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserializesAggregateRequest() throws Exception {
        String json = """
            {
              "params": {
                "type": "AGGREGATE_GROUP_COUNT",
                "childEntity": "Order",
                "parentEntity": "Customer"
              },
              "iterations": 10,
              "databaseIds": ["db1", "db2"]
            }
            """;
        StartScenarioRunRequest request = mapper.readValue(json, StartScenarioRunRequest.class);
        assertThat(request).isNotNull();
        assertThat(request.params()).isInstanceOf(AggregateParams.class);
        AggregateParams agg = (AggregateParams) request.params();
        assertThat(agg.childEntity()).isEqualTo("Order");
        assertThat(agg.parentEntity()).isEqualTo("Customer");
        assertThat(agg.type()).isEqualTo(ScenarioType.AGGREGATE_GROUP_COUNT);
        assertThat(request.iterationsOrDefault()).isEqualTo(10);
        assertThat(request.databaseIds()).containsExactly("db1", "db2");
    }

    @Test
    void deserializesRangeRequest() throws Exception {
        String json = """
            {
              "params": {
                "type": "RANGE_FILTER",
                "entityName": "Order",
                "attribute": "amount",
                "min": 0.0,
                "max": 100.0
              },
              "iterations": 5,
              "databaseIds": ["db1"]
            }
            """;
        StartScenarioRunRequest request = mapper.readValue(json, StartScenarioRunRequest.class);
        assertThat(request.params()).isInstanceOf(RangeParams.class);
    }

    @Test
    void deserializesTraversalRequest() throws Exception {
        String json = """
            {
              "params": {
                "type": "GRAPH_TRAVERSAL",
                "startEntity": "Customer",
                "startLogicalId": "uuid-1",
                "depth": 3
              },
              "iterations": 10,
              "databaseIds": ["db1"]
            }
            """;
        StartScenarioRunRequest request = mapper.readValue(json, StartScenarioRunRequest.class);
        assertThat(request.params()).isInstanceOf(TraversalParams.class);
    }

    @Test
    void deserializesKnnRequest() throws Exception {
        String json = """
            {
              "params": {
                "type": "VECTOR_KNN",
                "entityName": "Product",
                "vectorAttribute": "embedding",
                "queryVector": [0.1, 0.2, 0.3],
                "topK": 10
              },
              "iterations": 5,
              "databaseIds": ["db1"]
            }
            """;
        StartScenarioRunRequest request = mapper.readValue(json, StartScenarioRunRequest.class);
        assertThat(request.params()).isInstanceOf(KnnParams.class);
    }

    @Test
    void roundTripsSerialization() throws Exception {
        StartScenarioRunRequest original = new StartScenarioRunRequest(
                new AggregateParams("Order", "Customer"),
                10,
                java.util.List.of("db1"));
        String json = mapper.writeValueAsString(original);
        StartScenarioRunRequest parsed = mapper.readValue(json, StartScenarioRunRequest.class);
        assertThat(parsed.params()).isInstanceOf(AggregateParams.class);
    }

    @Test
    void canDeserializeBareScenarioParams() throws Exception {
        String json = """
            {
              "type": "AGGREGATE_GROUP_COUNT",
              "childEntity": "Order",
              "parentEntity": "Customer"
            }
            """;
        ScenarioParams params = mapper.readValue(json, ScenarioParams.class);
        assertThat(params).isInstanceOf(AggregateParams.class);
    }
}
