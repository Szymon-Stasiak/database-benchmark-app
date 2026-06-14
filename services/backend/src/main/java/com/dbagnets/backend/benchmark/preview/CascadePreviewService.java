package com.dbagnets.backend.benchmark.preview;

import com.dbagnets.backend.benchmark.registry.EntityIdRegistry;
import com.dbagnets.backend.benchmark.schema.LogicalRelationship;
import com.dbagnets.backend.benchmark.schema.LogicalSchema;
import com.dbagnets.backend.benchmark.schema.RelationshipCardinality;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CascadePreviewService {

    private static final int MAX_DEPTH = 6;

    private final EntityIdRegistry registry;

    public RunPreview build(String benchmarkId,
                            LogicalSchema schema,
                            String rootEntity,
                            int sampleSize,
                            boolean includeChildren) {
        long pool = registry.countLogicalIds(benchmarkId, rootEntity);
        int effectiveSample = (int) Math.min(sampleSize, pool);
        List<RunPreview.CascadeImpact> cascade = includeChildren
                ? walkChildren(schema, rootEntity, effectiveSample)
                : List.of();
        return new RunPreview(rootEntity, effectiveSample, pool, cascade);
    }

    private List<RunPreview.CascadeImpact> walkChildren(LogicalSchema schema,
                                                         String root,
                                                         int rootCount) {
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
                double ratio = defaultRatio(rel.cardinality());
                long childCount = Math.max(0L, Math.round(frame.count * ratio));
                out.add(new RunPreview.CascadeImpact(
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

    private double defaultRatio(RelationshipCardinality cardinality) {
        return switch (cardinality) {
            case ONE_TO_ONE -> 1.0;
            case ONE_TO_MANY -> 5.0;
            case MANY_TO_MANY -> 3.0;
        };
    }

    private record Frame(String entity, long count, int depth) {
    }
}
