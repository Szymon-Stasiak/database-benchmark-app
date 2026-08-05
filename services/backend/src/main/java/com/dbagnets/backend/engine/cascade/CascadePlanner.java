package com.dbagnets.backend.engine.cascade;

import com.dbagnets.backend.engine.schema.LogicalRelationship;
import com.dbagnets.backend.engine.schema.LogicalSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class CascadePlanner {

    public CascadePlan plan(LogicalSchema schema, List<LeafChoice> leafChoices) {
        return plan(schema, leafChoices, Map.of());
    }

    public CascadePlan plan(LogicalSchema schema, List<LeafChoice> leafChoices, Map<String, Double> ratioOverrides) {
        if (leafChoices == null || leafChoices.isEmpty()) {
            throw new IllegalArgumentException("At least one leaf entity must be chosen");
        }

        Map<String, Long> derived = new HashMap<>();
        Map<String, List<CascadeEdge>> incomingByChild = new HashMap<>();

        Deque<String> queue = new ArrayDeque<>();
        for (LeafChoice leaf : leafChoices) {
            schema.requireEntity(leaf.entityName());
            derived.merge(leaf.entityName(), leaf.recordCount(), Math::max);
            queue.add(leaf.entityName());
        }

        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            String childName = queue.pollFirst();
            if (!visited.add(childName)) continue;

            List<LogicalRelationship> parents = schema.relationshipsTargeting(childName);
            long childCount = derived.getOrDefault(childName, 0L);

            for (LogicalRelationship rel : parents) {
                String parentName = rel.parentEntity();
                String fk = ForeignKeyResolver.resolve(schema, rel);
                double ratio = ratioOverrides.getOrDefault(rel.name(), rel.cardinality().defaultRatio());

                long parentCount = (long) Math.ceil(childCount / ratio);
                derived.merge(parentName, parentCount, Math::max);

                incomingByChild
                        .computeIfAbsent(childName, k -> new ArrayList<>())
                        .add(new CascadeEdge(parentName, childName, fk, ratio));

                queue.add(parentName);
            }
        }

        List<String> ordered = topologicalSort(derived.keySet(), schema);
        List<CascadeNode> nodes = new ArrayList<>(ordered.size());
        for (String entity : ordered) {
            nodes.add(new CascadeNode(
                    entity,
                    derived.getOrDefault(entity, 0L),
                    incomingByChild.getOrDefault(entity, List.of())));
        }
        return new CascadePlan(nodes);
    }

    private static List<String> topologicalSort(Set<String> nodes, LogicalSchema schema) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (String n : nodes) {
            indegree.put(n, 0);
            adj.put(n, new ArrayList<>());
        }
        for (LogicalRelationship rel : schema.relationships()) {
            if (rel.isManyToMany()) continue;
            String parent = rel.parentEntity();
            String child = rel.childEntity();
            if (!nodes.contains(parent) || !nodes.contains(child)) continue;
            adj.get(parent).add(child);
            indegree.merge(child, 1, Integer::sum);
        }

        Deque<String> ready = new ArrayDeque<>();
        indegree.entrySet().stream()
                .filter(e -> e.getValue() == 0)
                .map(Map.Entry::getKey)
                .sorted(Comparator.naturalOrder())
                .forEach(ready::add);

        List<String> order = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            String n = ready.pollFirst();
            order.add(n);
            for (String child : adj.get(n)) {
                int updated = indegree.merge(child, -1, Integer::sum);
                if (updated == 0) ready.add(child);
            }
        }
        if (order.size() != nodes.size()) {
            throw new IllegalStateException("Cycle detected in entity relationships; cascade planning is undefined");
        }
        return order;
    }
}