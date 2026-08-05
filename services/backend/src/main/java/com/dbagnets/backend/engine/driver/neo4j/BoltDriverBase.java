package com.dbagnets.backend.engine.driver.neo4j;

import com.dbagnets.backend.engine.cascade.CascadeEdge;
import com.dbagnets.backend.engine.cascade.CascadeNode;
import com.dbagnets.backend.engine.datagen.GeneratedRow;
import com.dbagnets.backend.engine.driver.BatchSizes;
import com.dbagnets.backend.engine.driver.CascadeBfsState;
import com.dbagnets.backend.engine.driver.ConflictDetector;
import com.dbagnets.backend.engine.driver.DeleteContext;
import com.dbagnets.backend.engine.driver.EngineDriver;
import com.dbagnets.backend.engine.driver.DriverValues;
import com.dbagnets.backend.engine.driver.EntityOutcome;
import com.dbagnets.backend.engine.driver.InsertAccumulator;
import com.dbagnets.backend.engine.driver.InsertContext;
import com.dbagnets.backend.engine.driver.ReadContext;
import com.dbagnets.backend.engine.driver.ScenarioTimings;
import com.dbagnets.backend.engine.registry.EntityIdRegistry.RegistryEntry;
import com.dbagnets.backend.engine.scenario.AggregateParams;
import com.dbagnets.backend.engine.scenario.KnnParams;
import com.dbagnets.backend.engine.scenario.RangeParams;
import com.dbagnets.backend.engine.scenario.ResultCanonicalizer;
import com.dbagnets.backend.engine.scenario.ScenarioContext;
import com.dbagnets.backend.engine.scenario.TraversalParams;
import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalEntity;
import com.dbagnets.backend.engine.timing.RecordedId;
import com.dbagnets.backend.engine.timing.TimedOperation;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;

import java.util.ArrayList;
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
        InsertAccumulator acc = new InsertAccumulator();
        long extraDbTimeNs = 0L;
        long wireStart = System.nanoTime();
        try (Session session = neo.session()) {
            for (CascadeNode node : ctx.plan().nodesInInsertOrder()) {
                List<GeneratedRow> rows = ctx.rowsByEntity().get(node.entityName());
                if (rows == null || rows.isEmpty()) continue;
                EntityOutcome nodeOutcome = createNodes(session, ctx, node, rows);
                acc.accept(nodeOutcome);
                for (CascadeEdge edge : node.incomingFromParents()) {
                    extraDbTimeNs += createRelationships(session, ctx, node, rows, edge);
                }
                ctx.progress().onEntityFinished(node.entityName());
            }
        }
        long wireTimeNs = System.nanoTime() - wireStart;
        TimedOperation base = acc.finish(wireTimeNs);
        return TimedOperation.builder()
                .dbTimeNs(base.dbTimeNs() + extraDbTimeNs)
                .wireTimeNs(base.wireTimeNs())
                .rowsAffected(base.rowsAffected())
                .conflictsSkipped(base.conflictsSkipped())
                .recordedIds(base.recordedIds())
                .build();
    }

    @Override
    public TimedOperation read(ReadContext ctx) {
        org.neo4j.driver.Driver neo = driverCache.get(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        String label = ctx.entityName();
        String pk = requiredPkName(ctx.schema(), label, "Entity missing PK: " + label);
        com.dbagnets.backend.engine.driver.ReadDepth depth = ctx.readDepth() == null ? com.dbagnets.backend.engine.driver.ReadDepth.NONE : ctx.readDepth();
        String cypher;
        if (depth == com.dbagnets.backend.engine.driver.ReadDepth.NONE) {
            cypher = "MATCH (n:`" + label + "` {" + pk + ": $id}) RETURN n";
        } else {
            int maxDepth = depth == com.dbagnets.backend.engine.driver.ReadDepth.ONE_HOP ? 1 : com.dbagnets.backend.engine.driver.ReadDepth.FULL_CASCADE_MAX_DEPTH;
            var chain = com.dbagnets.backend.engine.driver.pg.PgScenarios.resolveChain(ctx.schema(), label, maxDepth);
            if (chain.isEmpty()) {
                cypher = "MATCH (n:`" + label + "` {" + pk + ": $id}) RETURN n";
            } else {
                java.util.Set<String> relTypes = new java.util.LinkedHashSet<>();
                java.util.Set<String> childLabels = new java.util.LinkedHashSet<>();
                for (var level : chain) {
                    relTypes.add((level.parentEntity() + "_" + level.childEntity()).toUpperCase());
                    childLabels.add(level.childEntity());
                }
                String relPattern = relTypes.stream().map(t -> "`" + t + "`").collect(java.util.stream.Collectors.joining("|"));
                String labelFilter = childLabels.stream().map(l -> "'" + l + "'").collect(java.util.stream.Collectors.joining(", "));
                cypher = "MATCH (n:`" + label + "` {" + pk + ": $id}) " + "OPTIONAL MATCH (n)-[:" + relPattern + "*1.." + chain.size() + "]->(m) " + "WHERE m IS NULL OR any(lbl IN labels(m) WHERE lbl IN [" + labelFilter + "]) " + "RETURN n, collect(DISTINCT m) AS descendants";
            }
        }

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsRead = 0L;

        boolean cascading = depth != com.dbagnets.backend.engine.driver.ReadDepth.NONE && cypher.contains("collect(DISTINCT m)");
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

        return TimedOperation.builder().dbTimeNs(totalDbTimeNs).wireTimeNs(wireTimeNs).rowsAffected(rowsRead).sampleDbTimeNs(samples).build();
    }

    @Override
    public TimedOperation delete(DeleteContext ctx) {
        org.neo4j.driver.Driver neo = driverCache.get(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        String label = ctx.entityName();
        String pk = requiredPkName(ctx.schema(), label, "Entity missing PK: " + label);
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

        return TimedOperation.builder().dbTimeNs(totalDbTimeNs).wireTimeNs(wireTimeNs).rowsAffected(rowsAffected).sampleDbTimeNs(samples).cascadeDeletedByEntity(cascadeAccumulator).build();
    }

    private void cascadeChildrenBfs(Session session, com.dbagnets.backend.engine.schema.LogicalSchema schema, String rootLabel, Object rootId, java.util.Map<String, java.util.List<String>> accumulator) {
        CascadeBfsState state = new CascadeBfsState(rootLabel, rootId, 16);
        while (state.hasNext()) {
            String cur = state.poll();
            if (!state.visit(cur)) continue;
            java.util.List<Object> curIds = state.idsFor(cur);
            if (curIds == null || curIds.isEmpty()) continue;
            String parentPk = pkName(schema, cur);
            if (parentPk == null) continue;

            for (var rel : schema.relationships()) {
                if (!rel.parentEntity().equalsIgnoreCase(cur)) continue;
                String childLabel = rel.childEntity();
                if (childLabel.equalsIgnoreCase(cur)) continue;
                String childPk = pkName(schema, childLabel);
                if (childPk == null) continue;

                String relType = (cur + "_" + childLabel).toUpperCase();
                String cypher = "UNWIND $ids AS pid " + "MATCH (p:`" + cur + "` {" + parentPk + ": pid})-[:`" + relType + "`]->(c:`" + childLabel + "`) " + "RETURN DISTINCT c." + childPk + " AS id";
                try {
                    var result = session.run(cypher, Map.of("ids", curIds));
                    java.util.List<Object> collected = new java.util.ArrayList<>();
                    while (result.hasNext()) {
                        Object v = result.next().get("id").asObject();
                        if (v != null) collected.add(v);
                    }
                    if (!collected.isEmpty()) state.addChildren(childLabel, collected);
                } catch (Exception ex) {
                    log.debug("Bolt cascade scan failed for {} → {}: {}", cur, childLabel, ex.getMessage());
                }
            }
        }

        for (String entityName : state.reversedEntityOrder()) {
            if (entityName.equalsIgnoreCase(rootLabel)) continue;
            java.util.List<Object> ids = state.idsFor(entityName);
            if (ids == null || ids.isEmpty()) continue;
            String childPk = pkName(schema, entityName);
            if (childPk == null) continue;
            String cypher = "UNWIND $ids AS pid MATCH (n:`" + entityName + "` {" + childPk + ": pid}) DETACH DELETE n";
            try {
                session.run(cypher, Map.of("ids", ids)).consume();
                accumulator.computeIfAbsent(entityName, k -> new java.util.ArrayList<>()).addAll(ids.stream().map(String::valueOf).toList());
            } catch (Exception ex) {
                log.warn("Bolt cascade delete failed for {}: {}", entityName, ex.getMessage());
            }
        }
    }

    private EntityOutcome createNodes(Session session, InsertContext ctx, CascadeNode node, List<GeneratedRow> rows) {
        EntityOutcome outcome = new EntityOutcome();
        String label = node.entityName();
        int batchSize = BatchSizes.effective(ctx, 10_000);
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

    private long createRelationships(Session session, InsertContext ctx, CascadeNode node, List<GeneratedRow> rows, CascadeEdge edge) {
        String parentLabel = edge.parentEntity();
        String childLabel = node.entityName();
        String cypher = getString(ctx, parentLabel, childLabel);

        List<Map<String, Object>> pairs = new ArrayList<>(rows.size());
        for (GeneratedRow row : rows) {
            Object parentRef = row.get(edge.fkColumnInChild());
            if (parentRef == null) continue;
            pairs.add(Map.of("parentId", parentRef, "childId", row.logicalId()));
        }
        if (pairs.isEmpty()) return 0L;

        int chunkSize = BatchSizes.effective(ctx, 10_000);
        long totalDbTimeNs = 0L;
        for (int from = 0; from < pairs.size(); from += chunkSize) {
            int to = Math.min(from + chunkSize, pairs.size());
            List<Map<String, Object>> chunk = pairs.subList(from, to);
            try {
                long start = System.nanoTime();
                session.run(cypher, Map.of("pairs", chunk)).consume();
                totalDbTimeNs += System.nanoTime() - start;
            } catch (Exception ex) {
                log.warn("{} relationship MERGE failed {} -> {} chunk [{}, {}): {}", engine(), parentLabel, childLabel, from, to, ex.getMessage());
            }
        }
        return totalDbTimeNs;
    }

    private static String getString(InsertContext ctx, String parentLabel, String childLabel) {
        String relType = (parentLabel + "_" + childLabel).toUpperCase();
        String childPk = requiredPkName(ctx.schema(), childLabel, "Child entity missing PK: " + childLabel);
        String parentPk = requiredPkName(ctx.schema(), parentLabel, "Parent entity missing PK: " + parentLabel);

        return "UNWIND $pairs AS pair " + "MATCH (p:`" + parentLabel + "` {" + parentPk + ": pair.parentId}) " + "MATCH (c:`" + childLabel + "` {" + childPk + ": pair.childId}) " + "MERGE (p)-[:`" + relType + "`]->(c)";
    }

    private Map<String, Object> toMap(GeneratedRow row) {
        return DriverValues.rowToMap(row);
    }

    @Override
    public ScenarioOutcome runScenario(ScenarioContext ctx) throws Exception {
        org.neo4j.driver.Driver neo = driverCache.get(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        try (Session session = neo.session()) {
            return ScenarioTimings.execute(() -> switch (ctx.params()) {
                case AggregateParams p -> {
                    Map<String, Long> grouped = BoltScenarios.executeAggregate(session, ctx.schema(), p.parentEntity(), p.childEntity());
                    yield ResultCanonicalizer.build(grouped, grouped.size());
                }
                case RangeParams p -> {
                    long count = BoltScenarios.executeRangeCount(session, ctx.schema(), p.entityName(), p.attribute(), p.min(), p.max());
                    yield ResultCanonicalizer.build(Map.of("count", count), count);
                }
                case TraversalParams p -> {
                    List<String> ids = BoltScenarios.executeTraversal(session, ctx.schema(), p.startEntity(), p.startLogicalId(), p.depth());
                    yield ResultCanonicalizer.build(ids, ids.size());
                }
                case KnnParams ignored ->
                        throw new UnsupportedOperationException(engine() + " does not support VECTOR_KNN");
            });
        }
    }

    private static String pkName(com.dbagnets.backend.engine.schema.LogicalSchema schema, String entityName) {
        return schema.findEntity(entityName).flatMap(LogicalEntity::primaryKey).map(LogicalAttribute::name).orElse(null);
    }

    private static String requiredPkName(com.dbagnets.backend.engine.schema.LogicalSchema schema, String entityName, String errorMessage) {
        return schema.requireEntity(entityName).primaryKey().orElseThrow(() -> new IllegalStateException(errorMessage)).name();
    }
}
