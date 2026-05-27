package com.dbagnets.backend.insert.service;

import com.dbagnets.backend.entity.Benchmark;
import com.dbagnets.backend.insert.cascade.CascadePlan;
import com.dbagnets.backend.insert.cascade.CascadeResolver;
import com.dbagnets.backend.insert.cascade.EdgeRatioOverride;
import com.dbagnets.backend.insert.cascade.EntityNode;
import com.dbagnets.backend.insert.cascade.FkColumnHeuristics;
import com.dbagnets.backend.insert.model.AttributeChoice;
import com.dbagnets.backend.insert.model.CascadePreviewResponse;
import com.dbagnets.backend.insert.model.EntityChoice;
import com.dbagnets.backend.insert.schema.LogicalAttribute;
import com.dbagnets.backend.insert.schema.LogicalEntity;
import com.dbagnets.backend.insert.schema.LogicalSchema;
import com.dbagnets.backend.insert.schema.LogicalSchemaLoader;
import com.dbagnets.backend.repository.BenchmarkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class EntityCatalogService {

    private final BenchmarkRepository benchmarkRepository;
    private final LogicalSchemaLoader schemaLoader;

    public EntityCatalogService(BenchmarkRepository benchmarkRepository, LogicalSchemaLoader schemaLoader) {
        this.benchmarkRepository = benchmarkRepository;
        this.schemaLoader = schemaLoader;
    }

    @Transactional(readOnly = true)
    public List<EntityChoice> listEntities(String benchmarkId) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId)
            .orElseThrow(() -> new NoSuchElementException("Benchmark not found: " + benchmarkId));
        return schemaLoader.load(benchmark)
            .map(this::toChoices)
            .orElse(List.of());
    }

    /**
     * Resolves a set of leaf entity names + their record counts (+ optional ratio overrides) into
     * a full {@link CascadePreviewResponse} the frontend uses to render the cascade-aware picker.
     */
    @Transactional(readOnly = true)
    public CascadePreviewResponse cascadePreview(
        String benchmarkId,
        List<String> leafEntityNames,
        Map<String, Integer> leafCounts,
        List<EdgeRatioOverride> overrides
    ) {
        Benchmark benchmark = benchmarkRepository.findById(benchmarkId)
            .orElseThrow(() -> new NoSuchElementException("Benchmark not found: " + benchmarkId));
        LogicalSchema schema = schemaLoader.load(benchmark)
            .orElseThrow(() -> new IllegalStateException("Benchmark has no logical schema yet"));

        CascadePlan plan = CascadeResolver.resolve(schema, leafEntityNames, overrides, leafCounts);

        Map<String, LogicalEntity> byName = new LinkedHashMap<>();
        for (LogicalEntity e : schema.entitiesOrEmpty()) byName.put(e.name().toLowerCase(), e);

        List<String> leafLower = leafEntityNames.stream().map(String::toLowerCase).toList();

        List<CascadePreviewResponse.PreviewEntity> previewEntities = new ArrayList<>();
        for (EntityNode node : plan.orderedEntities()) {
            previewEntities.add(new CascadePreviewResponse.PreviewEntity(
                node.name(), node.recordCount(),
                leafLower.contains(node.name().toLowerCase()),
                node.parents()
            ));
        }

        List<CascadePreviewResponse.PreviewEdge> previewEdges = new ArrayList<>();
        for (var edge : plan.edges()) {
            LogicalEntity childEntity = byName.get(edge.childEntity().toLowerCase());
            String fkColumn = childEntity == null ? null
                : FkColumnHeuristics.findFkColumn(childEntity, edge.parentEntity());
            previewEdges.add(new CascadePreviewResponse.PreviewEdge(
                edge.childEntity(),
                edge.parentEntity(),
                edge.cardinality().name(),
                edge.cardinality().defaultRatio(),
                edge.ratio(),
                fkColumn
            ));
        }
        return new CascadePreviewResponse(previewEntities, previewEdges);
    }

    /* ===================== Internal mapping helpers ===================== */

    private List<EntityChoice> toChoices(LogicalSchema schema) {
        return schema.entitiesOrEmpty().stream().map(this::toChoice).toList();
    }

    private EntityChoice toChoice(LogicalEntity entity) {
        List<AttributeChoice> attrs = entity.attributesOrEmpty().stream()
            .map(this::toAttribute)
            .toList();
        return new EntityChoice(entity.name(), entity.description(), attrs);
    }

    private AttributeChoice toAttribute(LogicalAttribute attribute) {
        var c = attribute.constraintsOrDefault();
        return new AttributeChoice(
            attribute.name(),
            attribute.dataType(),
            attribute.description(),
            c.isPrimaryKey(),
            c.isNullable()
        );
    }
}
