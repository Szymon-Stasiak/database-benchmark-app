package com.dbagnets.backend.benchmark.schema;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class EmbeddingMap {

    private final Map<String, EmbeddingMapping> byEntity;

    private EmbeddingMap(Map<String, EmbeddingMapping> byEntity) {
        this.byEntity = byEntity;
    }

    public static EmbeddingMap empty() {
        return new EmbeddingMap(Map.of());
    }

    public static EmbeddingMap from(List<EmbeddingMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return empty();
        }
        Map<String, EmbeddingMapping> indexed = mappings.stream()
                .collect(Collectors.toUnmodifiableMap(
                        m -> m.entityName().toLowerCase(Locale.ROOT),
                        m -> m,
                        (a, b) -> a));
        return new EmbeddingMap(indexed);
    }

    public boolean isEmbedded(String entityName) {
        EmbeddingMapping m = byEntity.get(entityName.toLowerCase(Locale.ROOT));
        return m != null && m.isEmbedded();
    }

    public Optional<EmbeddingMapping> lookup(String entityName) {
        return Optional.ofNullable(byEntity.get(entityName.toLowerCase(Locale.ROOT)));
    }
}
