package com.dbagnets.backend.insert.cascade;

import com.dbagnets.backend.insert.schema.LogicalEntity;
import com.dbagnets.backend.insert.schema.LogicalRelationship;
import com.dbagnets.backend.insert.schema.LogicalSchema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves a set of user-picked leaf entities into a full {@link CascadePlan}:
 *
 * <ol>
 *   <li>Walks {@link LogicalRelationship} edges to pull in every parent transitively required to
 *       satisfy a foreign-key cascade.</li>
 *   <li>Sorts the result topologically (parents before children) so the orchestrator can insert
 *       safely with FK constraints enabled.</li>
 *   <li>Applies the supplied per-edge ratios (falling back to {@link Cardinality#defaultRatio()})
 *       to {@link RecordCountCalculator} which propagates record counts up the parent chain.</li>
 * </ol>
 *
 * <p>Convention for direction: a {@code LogicalRelationship} with cardinality {@code 1:N} treats
 * its {@code source_entity} as the parent (the {@code 1} side, whose PK is referenced) and
 * {@code target_entity} as the child (the {@code N} side that holds the FK). {@code M:N} edges
 * are recorded but skipped during cascade resolution — they imply a junction table that lives
 * outside this scope.
 *
 * <p>Pure-Java, no Spring dependencies — instantiated per call.
 */
public final class CascadeResolver {

    private CascadeResolver() {}

    public static CascadePlan resolve(
        LogicalSchema schema,
        List<String> leafEntityNames,
        List<EdgeRatioOverride> ratioOverrides,
        Map<String, Integer> leafRecordCounts
    ) {
        Map<String, LogicalEntity> entitiesByName = new LinkedHashMap<>();
        for (LogicalEntity e : schema.entitiesOrEmpty()) {
            entitiesByName.put(normalize(e.name()), e);
        }

        Map<String, List<ParentRef>> parentsByChild = buildParentIndex(schema);
        Set<String> required = new HashSet<>();
        for (String leaf : leafEntityNames) {
            String key = normalize(leaf);
            if (!entitiesByName.containsKey(key)) {
                throw new IllegalArgumentException("Entity '" + leaf + "' not found in schema");
            }
            collectAncestors(key, parentsByChild, required);
        }

        List<String> orderedNames = topologicalOrder(required, parentsByChild);

        List<CascadeEdge> edges = resolveEdges(orderedNames, parentsByChild, ratioOverrides);

        Map<String, Integer> counts = RecordCountCalculator.compute(orderedNames, edges, leafRecordCounts);

        List<EntityNode> nodes = new ArrayList<>(orderedNames.size());
        for (String n : orderedNames) {
            LogicalEntity e = entitiesByName.get(n);
            List<String> parentNames = new ArrayList<>();
            for (ParentRef p : parentsByChild.getOrDefault(n, List.of())) {
                if (p.cardinality != Cardinality.MANY_TO_MANY) parentNames.add(p.parent);
            }
            nodes.add(new EntityNode(e.name(), e, counts.getOrDefault(n, 0), parentNames));
        }
        return new CascadePlan(nodes, edges);
    }

    /* ====================================================================== */
    /* Internal: parent index + topological sort                              */
    /* ====================================================================== */

    private static Map<String, List<ParentRef>> buildParentIndex(LogicalSchema schema) {
        Map<String, List<ParentRef>> idx = new HashMap<>();
        if (schema.relationships() == null) return idx;
        for (LogicalRelationship r : schema.relationships()) {
            if (r.sourceEntity() == null || r.targetEntity() == null) continue;
            Cardinality card = CardinalityParser.parse(r.cardinality());
            String parent;
            String child;
            switch (card) {
                case ONE_TO_MANY -> { parent = r.sourceEntity(); child = r.targetEntity(); }
                case ONE_TO_ONE -> { parent = r.sourceEntity(); child = r.targetEntity(); }
                case MANY_TO_MANY -> { parent = r.sourceEntity(); child = r.targetEntity(); }
                default -> { parent = r.sourceEntity(); child = r.targetEntity(); }
            }
            idx.computeIfAbsent(normalize(child), k -> new ArrayList<>())
                .add(new ParentRef(normalize(parent), card));
        }
        return idx;
    }

    private static void collectAncestors(
        String entity,
        Map<String, List<ParentRef>> parentsByChild,
        Set<String> visited
    ) {
        if (!visited.add(entity)) return;
        for (ParentRef p : parentsByChild.getOrDefault(entity, List.of())) {
            if (p.cardinality == Cardinality.MANY_TO_MANY) continue;
            collectAncestors(p.parent, parentsByChild, visited);
        }
    }

    /** Kahn's algorithm — produces a deterministic order honouring "parents before children". */
    private static List<String> topologicalOrder(
        Set<String> required,
        Map<String, List<ParentRef>> parentsByChild
    ) {
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        for (String e : required) {
            inDegree.putIfAbsent(e, 0);
            outgoing.putIfAbsent(e, new ArrayList<>());
        }
        for (String child : required) {
            for (ParentRef p : parentsByChild.getOrDefault(child, List.of())) {
                if (!required.contains(p.parent)) continue;
                if (p.cardinality == Cardinality.MANY_TO_MANY) continue;
                outgoing.computeIfAbsent(p.parent, k -> new ArrayList<>()).add(child);
                inDegree.merge(child, 1, Integer::sum);
            }
        }
        List<String> order = new ArrayList<>(required.size());
        List<String> ready = new ArrayList<>();
        for (var e : inDegree.entrySet()) {
            if (e.getValue() == 0) ready.add(e.getKey());
        }
        while (!ready.isEmpty()) {
            String head = ready.remove(0);
            order.add(head);
            for (String next : outgoing.getOrDefault(head, List.of())) {
                int v = inDegree.merge(next, -1, Integer::sum);
                if (v == 0) ready.add(next);
            }
        }
        if (order.size() != required.size()) {
            throw new IllegalStateException(
                "Schema has a relationship cycle among " + required + " — cannot resolve cascade");
        }
        return order;
    }

    private static List<CascadeEdge> resolveEdges(
        List<String> orderedNames,
        Map<String, List<ParentRef>> parentsByChild,
        List<EdgeRatioOverride> overrides
    ) {
        Map<String, Double> overrideMap = new HashMap<>();
        for (EdgeRatioOverride o : overrides == null ? List.<EdgeRatioOverride>of() : overrides) {
            overrideMap.put(edgeKey(o.childEntity(), o.parentEntity()), o.ratio());
        }
        Set<String> orderedSet = new HashSet<>(orderedNames);
        List<CascadeEdge> edges = new ArrayList<>();
        for (String child : orderedNames) {
            for (ParentRef p : parentsByChild.getOrDefault(child, List.of())) {
                if (!orderedSet.contains(p.parent)) continue;
                if (p.cardinality == Cardinality.MANY_TO_MANY) continue;
                double ratio = overrideMap.getOrDefault(
                    edgeKey(child, p.parent),
                    p.cardinality.defaultRatio());
                edges.add(new CascadeEdge(child, p.parent, p.cardinality, ratio));
            }
        }
        return edges;
    }

    private static String edgeKey(String child, String parent) {
        return normalize(child) + "->" + normalize(parent);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private record ParentRef(String parent, Cardinality cardinality) {}
}
