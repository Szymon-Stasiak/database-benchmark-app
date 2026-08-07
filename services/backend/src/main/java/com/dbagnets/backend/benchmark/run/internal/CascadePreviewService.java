package com.dbagnets.backend.benchmark.run.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.dbagnets.backend.benchmark.run.api.dto.CascadePreviewRequest;
import com.dbagnets.backend.benchmark.run.api.dto.CascadePreviewResponse;
import com.dbagnets.backend.benchmark.run.api.dto.EdgeRatioDto;
import com.dbagnets.backend.benchmark.run.api.dto.EntityCascadeChoiceDto;
import com.dbagnets.backend.engine.cascade.CascadeEdge;
import com.dbagnets.backend.engine.cascade.CascadeNode;
import com.dbagnets.backend.engine.cascade.CascadePlan;
import com.dbagnets.backend.engine.cascade.CascadePlanner;
import com.dbagnets.backend.engine.cascade.LeafChoice;
import com.dbagnets.backend.engine.registry.EntityIdRegistry;
import com.dbagnets.backend.engine.schema.LogicalRelationship;
import com.dbagnets.backend.engine.schema.LogicalSchema;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CascadePreviewService {

    private static final int MAX_DEPTH = 6;
    private static final String DEFAULT_CARDINALITY = "ONE_TO_MANY";

    private final EntityIdRegistry registry;
    private final CascadePlanner planner;

    public CascadePreviewResponse previewFromRequest(
            LogicalSchema schema, CascadePreviewRequest request) {
        CascadePlan plan = planFrom(schema, request.entities());
        return buildResponse(schema, plan, request);
    }

    private CascadePlan planFrom(LogicalSchema schema, List<EntityCascadeChoiceDto> entities) {
        List<LeafChoice> leaves =
                entities.stream()
                        .map(e -> new LeafChoice(e.entityName(), e.recordCount()))
                        .toList();
        Map<String, Double> overrides = new HashMap<>();
        for (EntityCascadeChoiceDto entity : entities) {
            for (EdgeRatioDto edge : entity.edgeRatios()) {
                overrides.put(edge.parentEntity() + "_" + edge.childEntity(), edge.ratio());
            }
        }
        return planner.plan(schema, leaves, overrides);
    }

    private CascadePreviewResponse buildResponse(
            LogicalSchema schema, CascadePlan plan, CascadePreviewRequest request) {
        List<String> leafNames =
                request.entities().stream().map(EntityCascadeChoiceDto::entityName).toList();
        List<CascadePreviewResponse.CascadePreviewEntity> entities =
                plan.nodesInInsertOrder().stream()
                        .map(
                                node ->
                                        new CascadePreviewResponse.CascadePreviewEntity(
                                                node.entityName(),
                                                node.recordCount(),
                                                leafNames.contains(node.entityName()),
                                                node.incomingFromParents().stream()
                                                        .map(CascadeEdge::parentEntity)
                                                        .toList()))
                        .toList();
        List<CascadePreviewResponse.CascadePreviewEdge> edges = new ArrayList<>();
        for (CascadeNode node : plan.nodesInInsertOrder()) {
            for (CascadeEdge edge : node.incomingFromParents()) {
                LogicalRelationship rel =
                        schema.relationships().stream()
                                .filter(
                                        r ->
                                                r.parentEntity().equals(edge.parentEntity())
                                                        && r.childEntity()
                                                                .equals(edge.childEntity()))
                                .findFirst()
                                .orElse(null);
                String cardinality = rel == null ? DEFAULT_CARDINALITY : rel.cardinality().name();
                double defaultRatio =
                        rel == null ? edge.parentToChildRatio() : rel.cardinality().defaultRatio();
                edges.add(
                        new CascadePreviewResponse.CascadePreviewEdge(
                                edge.childEntity(),
                                edge.parentEntity(),
                                cardinality,
                                defaultRatio,
                                edge.parentToChildRatio(),
                                edge.fkColumnInChild()));
            }
        }
        return new CascadePreviewResponse(entities, edges);
    }

    public RunPreview build(
            String benchmarkId,
            LogicalSchema schema,
            String rootEntity,
            int sampleSize,
            boolean includeChildren) {
        long pool = registry.countLogicalIds(benchmarkId, rootEntity);
        int effectiveSample = (int) Math.min(sampleSize, pool);
        List<RunPreview.CascadeImpact> cascade =
                includeChildren ? walkChildren(schema, rootEntity, effectiveSample) : List.of();
        return new RunPreview(rootEntity, effectiveSample, pool, cascade);
    }

    private List<RunPreview.CascadeImpact> walkChildren(
            LogicalSchema schema, String root, int rootCount) {
        List<RunPreview.CascadeImpact> out = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(root);
        Deque<Frame> queue = new ArrayDeque<>();
        queue.add(new Frame(root, rootCount, 0));

        while (!queue.isEmpty()) {
            Frame frame = queue.pollFirst();
            if (frame.depth >= MAX_DEPTH) continue;
            for (LogicalRelationship rel : schema.relationships()) {
                if (!rel.parentEntity().equalsIgnoreCase(frame.entity)) continue;
                String child = rel.childEntity();
                if (visited.contains(child)) continue;
                visited.add(child);
                double ratio = rel.cardinality().defaultRatio();
                long childCount = Math.max(0L, Math.round(frame.count * ratio));
                out.add(
                        new RunPreview.CascadeImpact(
                                child,
                                frame.entity,
                                rel.fkColumnInChild(),
                                rel.cardinality().name(),
                                ratio,
                                childCount,
                                frame.depth + 1));
                queue.add(new Frame(child, childCount, frame.depth + 1));
            }
        }
        return out;
    }

    private record Frame(String entity, long count, int depth) {}
}
