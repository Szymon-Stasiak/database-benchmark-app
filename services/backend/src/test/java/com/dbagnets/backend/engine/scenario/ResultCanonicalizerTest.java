package com.dbagnets.backend.engine.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ResultCanonicalizerTest {

    @Test
    void differentKeyOrderProducesSameHash() {
        Map<String, Long> a = new LinkedHashMap<>();
        a.put("alpha", 1L);
        a.put("beta", 2L);

        Map<String, Long> b = new LinkedHashMap<>();
        b.put("beta", 2L);
        b.put("alpha", 1L);

        String hashA = ResultCanonicalizer.hash(ResultCanonicalizer.canonicalize(a));
        String hashB = ResultCanonicalizer.hash(ResultCanonicalizer.canonicalize(b));
        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    void differentValuesProduceDifferentHash() {
        Map<String, Long> a = Map.of("alpha", 1L, "beta", 2L);
        Map<String, Long> b = Map.of("alpha", 1L, "beta", 3L);
        String hashA = ResultCanonicalizer.hash(ResultCanonicalizer.canonicalize(a));
        String hashB = ResultCanonicalizer.hash(ResultCanonicalizer.canonicalize(b));
        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    void nestedStructuresSorted() {
        Map<String, Object> nested =
                Map.of(
                        "outer", Map.of("z", 1, "a", 2),
                        "list", List.of(Map.of("b", 1, "a", 2)));
        String canonical = ResultCanonicalizer.canonicalize(nested);
        assertThat(canonical.indexOf("\"a\":2")).isLessThan(canonical.indexOf("\"z\":1"));
    }

    @Test
    void buildProducesAlignedHashAndJson() {
        Map<String, Long> data = Map.of("k1", 5L, "k2", 7L);
        ScenarioResult result = ResultCanonicalizer.build(data, data.size());
        assertThat(result.rowsReturned()).isEqualTo(2L);
        assertThat(result.canonicalHash()).hasSize(64);
        assertThat(result.resultJson()).contains("\"k1\":5").contains("\"k2\":7");
    }

    @Test
    void emptyMapHashIsStable() {
        String hash1 = ResultCanonicalizer.hash(ResultCanonicalizer.canonicalize(Map.of()));
        String hash2 = ResultCanonicalizer.hash(ResultCanonicalizer.canonicalize(Map.of()));
        assertThat(hash1).isEqualTo(hash2);
    }
}
