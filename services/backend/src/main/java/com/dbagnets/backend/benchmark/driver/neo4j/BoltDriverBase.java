package com.dbagnets.backend.benchmark.driver.neo4j;

import com.dbagnets.backend.benchmark.cascade.CascadeEdge;
import com.dbagnets.backend.benchmark.cascade.CascadeNode;
import com.dbagnets.backend.benchmark.datagen.GeneratedRow;
import com.dbagnets.backend.benchmark.driver.ConflictDetector;
import com.dbagnets.backend.benchmark.driver.DeleteContext;
import com.dbagnets.backend.benchmark.driver.EngineDriver;
import com.dbagnets.backend.benchmark.driver.InsertContext;
import com.dbagnets.backend.benchmark.driver.ReadContext;
import com.dbagnets.backend.benchmark.registry.EntityIdRegistry.RegistryEntry;
import com.dbagnets.backend.benchmark.scenario.AggregateParams;
import com.dbagnets.backend.benchmark.scenario.KnnParams;
import com.dbagnets.backend.benchmark.scenario.RangeParams;
import com.dbagnets.backend.benchmark.scenario.ResultCanonicalizer;
import com.dbagnets.backend.benchmark.scenario.ScenarioContext;
import com.dbagnets.backend.benchmark.scenario.ScenarioResult;
import com.dbagnets.backend.benchmark.scenario.TraversalParams;
import com.dbagnets.backend.benchmark.timing.RecordedId;
import com.dbagnets.backend.benchmark.timing.TimedOperation;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public abstract class BoltDriverBase implements EngineDriver {

    protected final BoltDriverProvider driverCache;

    protected BoltDriverBase(BoltDriverProvider driverCache) {
        this.driverCache = driverCache;
    }

    @Override
    public TimedOperation insert(InsertContext ctx) {
        org.neo4j.driver.Driver neo = driverCache.get(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());

        long totalDbTimeNs = 0L;
        long totalRowsAffected = 0L;
        int totalConflicts = 0;
        List<RecordedId> recordedIds = new ArrayList<>();

        long wireStart = System.nanoTime();
        try (Session session = neo.session()) {
            for (CascadeNode node : ctx.plan().nodesInInsertOrder()) {
                List<GeneratedRow> rows = ctx.rowsByEntity().get(node.entityName());
                if (rows == null || rows.isEmpty()) continue;
                NodeOutcome nodeOutcome = createNodes(session, ctx, node, rows);
                totalDbTimeNs += nodeOutcome.dbTimeNs;
                totalRowsAffected += nodeOutcome.rowsAffected;
                totalConflicts += nodeOutcome.conflicts;
                recordedIds.addAll(nodeOutcome.recordedIds);

                for (CascadeEdge edge : node.incomingFromParents()) {
                    totalDbTimeNs += createRelationships(session, ctx, node, rows, edge);
                }
                ctx.progress().onEntityFinished(node.entityName());
            }
        }
        long wireTimeNs = System.nanoTime() - wireStart;

        return TimedOperation.builder()
                .dbTimeNs(totalDbTimeNs)
                .wireTimeNs(wireTimeNs)
                .rowsAffected(totalRowsAffected)
                .conflictsSkipped(totalConflicts)
                .recordedIds(recordedIds)
                .build();
    }

    @Override
    public TimedOperation read(ReadContext ctx) {
        org.neo4j.driver.Driver neo = driverCache.get(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        String label = ctx.entityName();
        String pk = ctx.schema().requireEntity(label).primaryKey()
                .orElseThrow(() -> new IllegalStateException("Entity missing PK: " + label))
                .name();
        com.dbagnets.backend.benchmark.driver.ReadDepth depth = ctx.readDepth() == null
                ? com.dbagnets.backend.benchmark.driver.ReadDepth.NONE : ctx.readDepth();
        String cypher;
        if (depth == com.dbagnets.backend.benchmark.driver.ReadDepth.NONE) {
            cypher = "MATCH (n:`" + label + "` {" + pk + ": $id}) RETURN n";
        } else {
            int maxDepth = depth == com.dbagnets.backend.benchmark.driver.ReadDepth.ONE_HOP
                    ? 1
                    : com.dbagnets.backend.benchmark.driver.ReadDepth.FULL_CASCADE_MAX_DEPTH;
            var chain = com.dbagnets.backend.benchmark.driver.pg.PgScenarios.resolveChain(
                    ctx.schema(), label, maxDepth);
            if (chain.isEmpty()) {
                cypher = "MATCH (n:`" + label + "` {" + pk + ": $id}) RETURN n";
            } else {
                java.util.Set<String> relTypes = new java.util.LinkedHashSet<>();
                java.util.Set<String> childLabels = new java.util.LinkedHashSet<>();
                for (var level : chain) {
                    relTypes.add((level.parentEntity() + "_" + level.childEntity()).toUpperCase());
                    childLabels.add(level.childEntity());
                }
                String relPattern = relTypes.stream().map(t -> "`" + t + "`")
                        .collect(java.util.stream.Collectors.joining("|"));
                String labelFilter = childLabels.stream().map(l -> "'" + l + "'")
                        .collect(java.util.stream.Collectors.joining(", "));
                cypher = "MATCH (n:`" + label + "` {" + pk + ": $id}) "
                        + "OPTIONAL MATCH (n)-[:" + relPattern + "*1.." + chain.size() + "]->(m) "
                        + "WHERE m IS NULL OR any(lbl IN labels(m) WHERE lbl IN [" + labelFilter + "]) "
                        + "RETURN n, collect(DISTINCT m) AS descendants";
            }
        }

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsRead = 0L;

        boolean cascading = depth != com.dbagnets.backend.benchmark.driver.ReadDepth.NONE
                && cypher.contains("collect(DISTINCT m)");
        String collectKey = "descendants";

        long wireStart = System.nanoTime();
        try (Session session = neo.session()) {
            for (int i = 0; i < ctx.targets().size(); i++) {
                RegistryEntry entry = ctx.targets().get(i);
                long start = System.nanoTime();
                Result result = session.run(cypher, Map.of("id", entry.physicalId()));
                long rowsForThis = 0L;
                while (result.hasNext()) {
                    var record = result.next();
                    rowsForThis++;
                    if (cascading && record.containsKey(collectKey)) {
                        var collected = record.get(collectKey);
                        if (collected != null && !collected.isNull()) {
                            int size = collected.size();
                            if (size > 0) rowsForThis += size;
                        }
                    }
                }
                result.consume();
                long sampleNs = System.nanoTime() - start;
                samples[i] = sampleNs;
                totalDbTimeNs += sampleNs;
                rowsRead += rowsForThis;
            }
        }
        long wireTimeNs = System.nanoTime() - wireStart;

        return TimedOperation.builder()
                .dbTimeNs(totalDbTimeNs)
                .wireTimeNs(wireTimeNs)
                .rowsAffected(rowsRead)
                .sampleDbTimeNs(samples)
                .build();
    }

    @Override
    public TimedOperation delete(DeleteContext ctx) {
        org.neo4j.driver.Driver neo = driverCache.get(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        String label = ctx.entityName();
        String pk = ctx.schema().requireEntity(label).primaryKey()
                .orElseThrow(() -> new IllegalStateException("Entity missing PK: " + label))
                .name();
        String rootCypher = "MATCH (n:`" + label + "` {" + pk + ": $id}) DETACH DELETE n";

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsAffected = 0L;
        java.util.Map<String, java.util.List<String>> cascadeAccumulator = new java.util.LinkedHashMap<>();

        long wireStart = System.nanoTime();
        try (Session session = neo.session()) {
            for (int i = 0; i < ctx.targets().size(); i++) {
                RegistryEntry entry = ctx.targets().get(i);
                long start = System.nanoTime();
                if (ctx.includeChildren()) {
                    cascadeChildrenBfs(session, ctx.schema(), label, entry.physicalId(), cascadeAccumulator);
                }
                var summary = session.run(rootCypher, Map.of("id", entry.physicalId())).consume();
                long sampleNs = System.nanoTime() - start;
                samples[i] = sampleNs;
                totalDbTimeNs += sampleNs;
                rowsAffected += summary.counters().nodesDeleted();
            }
        }
        long wireTimeNs = System.nanoTime() - wireStart;

        return TimedOperation.builder()
                .dbTimeNs(totalDbTimeNs)
                .wireTimeNs(wireTimeNs)
                .rowsAffected(rowsAffected)
                .sampleDbTimeNs(samples)
                .cascadeDeletedByEntity(cascadeAccumulator)
                .build();
    }

    private void cascadeChildrenBfs(Session session,
                                     com.dbagnets.backend.benchmark.schema.LogicalSchema schema,
                                     String rootLabel,
                                     Object rootId,
                                     java.util.Map<String, java.util.List<String>> accumulator) {
        java.util.Map<String, java.util.List<Object>> byEntity = new java.util.LinkedHashMap<>();
        byEntity.put(rootLabel, new java.util.ArrayList<>(java.util.List.of(rootId)));
        java.util.Set<String> visited = new java.util.HashSet<>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        queue.add(rootLabel);

        int safety = 0;
        while (!queue.isEmpty() && safety++ < 16) {
            String cur = queue.poll();
            if (!visited.add(cur)) continue;
            java.util.List<Object> curIds = byEntity.get(cur);
            if (curIds == null || curIds.isEmpty()) continue;
            String parentPk = schema.findEntity(cur)
                    .flatMap(e -> e.primaryKey())
                    .map(a -> a.name())
                    .orElse(null);
            if (parentPk == null) continue;

            for (var rel : schema.relationships()) {
                if (!rel.parentEntity().equalsIgnoreCase(cur)) continue;
                String childLabel = rel.childEntity();
                if (childLabel.equalsIgnoreCase(cur)) continue;
                String childPk = schema.findEntity(childLabel)
                        .flatMap(e -> e.primaryKey())
                        .map(a -> a.name())
                        .orElse(null);
                if (childPk == null) continue;

                String relType = (cur + "_" + childLabel).toUpperCase();
                String cypher = "UNWIND $ids AS pid "
                        + "MATCH (p:`" + cur + "` {" + parentPk + ": pid})-[:`" + relType + "`]->(c:`" + childLabel + "`) "
                        + "RETURN DISTINCT c." + childPk + " AS id";
                try {
                    var result = session.run(cypher, Map.of("ids", curIds));
                    java.util.List<Object> collected = new java.util.ArrayList<>();
                    while (result.hasNext()) {
                        Object v = result.next().get("id").asObject();
                        if (v != null) collected.add(v);
                    }
                    if (!collected.isEmpty()) {
                        byEntity.computeIfAbsent(childLabel, k -> new java.util.ArrayList<>()).addAll(collected);
                        if (!visited.contains(childLabel)) queue.add(childLabel);
                    }
                } catch (Exception ex) {
                    log.debug("Bolt cascade scan failed for {} → {}: {}", cur, childLabel, ex.getMessage());
                }
            }
        }

        java.util.List<String> deletionOrder = new java.util.ArrayList<>(byEntity.keySet());
        java.util.Collections.reverse(deletionOrder);
        for (String entityName : deletionOrder) {
            if (entityName.equalsIgnoreCase(rootLabel)) continue;
            java.util.List<Object> ids = byEntity.get(entityName);
            if (ids == null || ids.isEmpty()) continue;
            String childPk = schema.findEntity(entityName)
                    .flatMap(e -> e.primaryKey())
                    .map(a -> a.name())
                    .orElse(null);
            if (childPk == null) continue;
            String cypher = "UNWIND $ids AS pid MATCH (n:`" + entityName + "` {" + childPk + ": pid}) DETACH DELETE n";
            try {
                session.run(cypher, Map.of("ids", ids)).consume();
                accumulator.computeIfAbsent(entityName, k -> new java.util.ArrayList<>())
                        .addAll(ids.stream().map(String::valueOf).toList());
            } catch (Exception ex) {
                log.warn("Bolt cascade delete failed for {}: {}", entityName, ex.getMessage());
            }
        }
    }

    private NodeOutcome createNodes(Session session,
                                     InsertContext ctx,
                                     CascadeNode node,
                                     List<GeneratedRow> rows) {
        NodeOutcome outcome = new NodeOutcome();
        String label = node.entityName();
        int batchSize = effectiveBatchSize(ctx);
        int totalBatches = Math.max(1, (int) Math.ceil((double) rows.size() / batchSize));
        String cypher = "UNWIND $batch AS row CREATE (n:`" + label + "`) SET n = row";

        int batchIndex = 0;
        for (int from = 0; from < rows.size(); from += batchSize) {
            int to = Math.min(from + batchSize, rows.size());
            List<GeneratedRow> slice = rows.subList(from, to);
            List<Map<String, Object>> batch = new ArrayList<>(slice.size());
            for (GeneratedRow row : slice) {
                batch.add(toMap(row));
            }
            try {
                long start = System.nanoTime();
                Result result = session.run(cypher, Map.of("batch", batch));
                var summary = result.consume();
                outcome.dbTimeNs += System.nanoTime() - start;
                outcome.rowsAffected += summary.counters().nodesCreated();
                slice.forEach(r -> outcome.recordedIds.add(new RecordedId(node.entityName(), r.logicalId(), r.logicalId())));
            } catch (Exception ex) {
                if (ConflictDetector.isConflict(engine(), ex)) {
                    outcome.conflicts += slice.size();
                    log.warn("{} conflict on {} batch {}/{}: {}", engine(), label, batchIndex, totalBatches, ex.getMessage());
                } else {
                    throw ex;
                }
            }
            batchIndex++;
            ctx.progress().onBatch(node.entityName(), batchIndex, totalBatches, to, rows.size());
        }
        return outcome;
    }

    private long createRelationships(Session session,
                                      InsertContext ctx,
                                      CascadeNode node,
                                      List<GeneratedRow> rows,
                                      CascadeEdge edge) {
        String parentLabel = edge.parentEntity();
        String childLabel = node.entityName();
        String relType = (parentLabel + "_" + childLabel).toUpperCase();
        String childPk = ctx.schema().requireEntity(childLabel).primaryKey()
                .orElseThrow(() -> new IllegalStateException("Child entity missing PK: " + childLabel))
                .name();
        String parentPk = ctx.schema().requireEntity(parentLabel).primaryKey()
                .orElseThrow(() -> new IllegalStateException("Parent entity missing PK: " + parentLabel))
                .name();

        String cypher = "UNWIND $pairs AS pair " +
                "MATCH (p:`" + parentLabel + "` {" + parentPk + ": pair.parentId}) " +
                "MATCH (c:`" + childLabel + "` {" + childPk + ": pair.childId}) " +
                "MERGE (p)-[:`" + relType + "`]->(c)";

        List<Map<String, Object>> pairs = new ArrayList<>(rows.size());
        for (GeneratedRow row : rows) {
            Object parentRef = row.get(edge.fkColumnInChild());
            if (parentRef == null) continue;
            pairs.add(Map.of("parentId", parentRef, "childId", row.logicalId()));
        }
        if (pairs.isEmpty()) return 0L;

        int chunkSize = effectiveBatchSize(ctx);
        long totalDbTimeNs = 0L;
        for (int from = 0; from < pairs.size(); from += chunkSize) {
            int to = Math.min(from + chunkSize, pairs.size());
            List<Map<String, Object>> chunk = pairs.subList(from, to);
            try {
                long start = System.nanoTime();
                session.run(cypher, Map.of("pairs", chunk)).consume();
                totalDbTimeNs += System.nanoTime() - start;
            } catch (Exception ex) {
                log.warn("{} relationship MERGE failed {} -> {} chunk [{}, {}): {}",
                        engine(), parentLabel, childLabel, from, to, ex.getMessage());
            }
        }
        return totalDbTimeNs;
    }

    private Map<String, Object> toMap(GeneratedRow row) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.values().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof float[] arr) {
                List<Double> list = new ArrayList<>(arr.length);
                for (float f : arr) list.add((double) f);
                map.put(entry.getKey(), list);
            } else if (value instanceof java.math.BigDecimal bd) {
                map.put(entry.getKey(), bd.doubleValue());
            } else if (value instanceof java.time.Instant ins) {
                map.put(entry.getKey(), ins.toString());
            } else if (value instanceof java.time.LocalDate ld) {
                map.put(entry.getKey(), ld.toString());
            } else {
                map.put(entry.getKey(), value);
            }
        }
        return map;
    }

    private int effectiveBatchSize(InsertContext ctx) {
        return switch (ctx.mode()) {
            case SINGLE -> 1;
            case BATCH -> Math.max(1, ctx.batchSize());
            case BULK -> Math.max(1, ctx.batchSize() > 0 ? ctx.batchSize() : 10_000);
        };
    }

    @Override
    public ScenarioOutcome runScenario(ScenarioContext ctx) {
        org.neo4j.driver.Driver neo = driverCache.get(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());

        long wireStart = System.nanoTime();
        long dbTimeNs;
        ScenarioResult result;
        try (Session session = neo.session()) {
            long start = System.nanoTime();
            result = switch (ctx.params()) {
                case AggregateParams p -> {
                    Map<String, Long> grouped = BoltScenarios.executeAggregate(session, ctx.schema(),
                            p.parentEntity(), p.childEntity());
                    yield ResultCanonicalizer.build(grouped, grouped.size());
                }
                case RangeParams p -> {
                    long count = BoltScenarios.executeRangeCount(session, ctx.schema(), p.entityName(),
                            p.attribute(), p.min(), p.max());
                    yield ResultCanonicalizer.build(Map.of("count", count), count);
                }
                case TraversalParams p -> {
                    List<String> ids = BoltScenarios.executeTraversal(session, ctx.schema(),
                            p.startEntity(), p.startLogicalId(), p.depth());
                    yield ResultCanonicalizer.build(ids, ids.size());
                }
                case KnnParams ignored -> throw new UnsupportedOperationException(
                        engine() + " does not support VECTOR_KNN");
            };
            dbTimeNs = System.nanoTime() - start;
        }
        long wireTimeNs = System.nanoTime() - wireStart;

        TimedOperation timed = TimedOperation.builder()
                .dbTimeNs(dbTimeNs)
                .wireTimeNs(wireTimeNs)
                .rowsAffected(result.rowsReturned())
                .sampleDbTimeNs(new long[] { dbTimeNs })
                .build();
        return new ScenarioOutcome(timed, result);
    }

    private static final class NodeOutcome {
        long dbTimeNs;
        long rowsAffected;
        int conflicts;
        List<RecordedId> recordedIds = new ArrayList<>();
    }
}
