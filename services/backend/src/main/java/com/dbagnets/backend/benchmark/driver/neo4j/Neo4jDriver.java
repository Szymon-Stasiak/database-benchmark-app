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
import com.dbagnets.backend.benchmark.timing.RecordedId;
import com.dbagnets.backend.benchmark.timing.TimedOperation;
import com.dbagnets.backend.entity.DatabaseEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class Neo4jDriver implements EngineDriver {

    private final Neo4jDriverCache driverCache;

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.NEO4J;
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
        String cypher = ctx.includeChildren()
                ? "MATCH (n:`" + label + "` {" + pk + ": $id}) OPTIONAL MATCH (n)-[r]-(m) RETURN n, collect(m) AS neighbours"
                : "MATCH (n:`" + label + "` {" + pk + ": $id}) RETURN n";

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsRead = 0L;

        long wireStart = System.nanoTime();
        try (Session session = neo.session()) {
            for (int i = 0; i < ctx.targets().size(); i++) {
                RegistryEntry entry = ctx.targets().get(i);
                long start = System.nanoTime();
                Result result = session.run(cypher, Map.of("id", entry.physicalId()));
                long rowsForThis = 0L;
                while (result.hasNext()) {
                    result.next();
                    rowsForThis++;
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
        String cypher = "MATCH (n:`" + label + "` {" + pk + ": $id}) DETACH DELETE n";

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsAffected = 0L;

        long wireStart = System.nanoTime();
        try (Session session = neo.session()) {
            for (int i = 0; i < ctx.targets().size(); i++) {
                RegistryEntry entry = ctx.targets().get(i);
                long start = System.nanoTime();
                var summary = session.run(cypher, Map.of("id", entry.physicalId())).consume();
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
                .build();
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
                    log.warn("Neo4j conflict on {} batch {}/{}: {}", label, batchIndex, totalBatches, ex.getMessage());
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
                log.warn("Neo4j relationship MERGE failed {} -> {} chunk [{}, {}): {}",
                        parentLabel, childLabel, from, to, ex.getMessage());
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

    private static final class NodeOutcome {
        long dbTimeNs;
        long rowsAffected;
        int conflicts;
        List<RecordedId> recordedIds = new ArrayList<>();
    }
}
