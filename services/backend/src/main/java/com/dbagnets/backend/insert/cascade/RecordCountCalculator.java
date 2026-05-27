package com.dbagnets.backend.insert.cascade;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Propagates leaf record counts up the parent chain using {@link CascadeEdge#ratio()}.
 *
 * <p>Rule: {@code count[parent] = ceil(sum_over_children(count[child] / ratio[child→parent]))}.
 * When the same parent appears via multiple children, the per-child requirements are summed so
 * the parent has enough rows for every dependent insert.
 */
public final class RecordCountCalculator {

    private RecordCountCalculator() {}

    public static Map<String, Integer> compute(
        List<String> orderedEntities,
        List<CascadeEdge> edges,
        Map<String, Integer> explicitCounts
    ) {
        Map<String, Integer> counts = new HashMap<>();
        Set<String> userProvided = new HashSet<>();
        if (explicitCounts != null) {
            for (var e : explicitCounts.entrySet()) {
                String key = normalize(e.getKey());
                counts.put(key, e.getValue());
                userProvided.add(key);
            }
        }
        // Walk children-first (reverse topological order). For each node, push demand onto its
        // parents immediately so a later iteration over the parent sees a non-zero count and can
        // propagate further upward (Order → User → Address).
        //
        // Combine semantics:
        //   - parent NOT user-provided  → counts.merge(parent, required, +)  ← accumulate all child demand
        //   - parent IS user-provided   → counts.merge(parent, required, max) ← honor user but never starve children
        for (int i = orderedEntities.size() - 1; i >= 0; i--) {
            String child = normalize(orderedEntities.get(i));
            int childCount = counts.getOrDefault(child, 0);
            if (childCount <= 0) continue;
            for (CascadeEdge e : edges) {
                if (!normalize(e.childEntity()).equals(child)) continue;
                String parent = normalize(e.parentEntity());
                int required = (int) Math.ceil(childCount / e.ratio());
                if (required < 1) required = 1;
                if (userProvided.contains(parent)) {
                    counts.merge(parent, required, Math::max);
                } else {
                    counts.merge(parent, required, Integer::sum);
                }
            }
        }
        // Ensure every entity in the plan has an entry (default 1 so the run never inserts zero
        // rows of a required ancestor that lacks a downstream demand path).
        for (String name : orderedEntities) {
            counts.putIfAbsent(normalize(name), 1);
        }
        return counts;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
