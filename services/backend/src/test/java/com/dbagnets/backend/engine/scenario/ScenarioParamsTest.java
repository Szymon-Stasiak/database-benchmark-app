package com.dbagnets.backend.engine.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ScenarioParamsTest {

    @Test
    void aggregateParamsRejectBlankEntities() {
        assertThatThrownBy(() -> new AggregateParams("", "Customer"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AggregateParams("Order", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aggregateParamsReportType() {
        AggregateParams p = new AggregateParams("Order", "Customer");
        assertThat(p.type()).isEqualTo(ScenarioType.AGGREGATE_GROUP_COUNT);
    }

    @Test
    void rangeParamsRejectInvertedBounds() {
        assertThatThrownBy(() -> new RangeParams("Order", "amount", 100.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min");
    }

    @Test
    void rangeParamsAllowEqualBounds() {
        RangeParams p = new RangeParams("Order", "amount", 50.0, 50.0);
        assertThat(p.min()).isEqualTo(50.0);
        assertThat(p.max()).isEqualTo(50.0);
    }

    @Test
    void traversalParamsClampDepth() {
        assertThatThrownBy(() -> new TraversalParams("Customer", "id-1", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TraversalParams("Customer", "id-1", 99))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void knnParamsDefensivelyCopyVector() {
        double[] vec = {0.1, 0.2, 0.3};
        KnnParams p = new KnnParams("Product", "embedding", vec, 10);
        vec[0] = 99.0;
        assertThat(p.queryVector()[0]).isEqualTo(0.1);
    }

    @Test
    void knnParamsRejectEmptyVector() {
        assertThatThrownBy(() -> new KnnParams("Product", "embedding", new double[0], 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void knnParamsRejectInvalidTopK() {
        assertThatThrownBy(() -> new KnnParams("Product", "embedding", new double[] {0.1}, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnnParams("Product", "embedding", new double[] {0.1}, 100_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sealedHierarchyExhaustivePatternMatch() {
        ScenarioParams[] all = {
            new AggregateParams("Order", "Customer"),
            new RangeParams("Order", "amount", 0.0, 100.0),
            new TraversalParams("Customer", "id-1", 3),
            new KnnParams("Product", "embedding", new double[] {0.1, 0.2}, 5)
        };
        for (ScenarioParams p : all) {
            String label =
                    switch (p) {
                        case AggregateParams ignored -> "agg";
                        case RangeParams ignored -> "range";
                        case TraversalParams ignored -> "trav";
                        case KnnParams ignored -> "knn";
                    };
            assertThat(label).isNotBlank();
        }
    }
}
