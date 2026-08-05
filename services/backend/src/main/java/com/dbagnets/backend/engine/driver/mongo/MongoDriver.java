package com.dbagnets.backend.engine.driver.mongo;

import com.dbagnets.backend.engine.cascade.CascadeNode;
import com.dbagnets.backend.engine.datagen.GeneratedRow;
import com.dbagnets.backend.engine.driver.BatchSizes;
import com.dbagnets.backend.engine.driver.BulkInsertLoop;
import com.dbagnets.backend.engine.driver.CascadeBfsState;
import com.dbagnets.backend.engine.driver.ConflictDetector;
import com.dbagnets.backend.engine.driver.DeleteContext;
import com.dbagnets.backend.engine.driver.DriverValues;
import com.dbagnets.backend.engine.driver.EngineDriver;
import com.dbagnets.backend.engine.driver.EntityOutcome;
import com.dbagnets.backend.engine.driver.FrontierBfs;
import com.dbagnets.backend.engine.driver.InsertContext;
import com.dbagnets.backend.engine.driver.InsertOuterLoop;
import com.dbagnets.backend.engine.driver.ReadContext;
import com.dbagnets.backend.engine.driver.ReadDepth;
import com.dbagnets.backend.engine.driver.SampledAccumulator;
import com.dbagnets.backend.engine.driver.ScenarioTimings;
import com.dbagnets.backend.engine.driver.pg.PgScenarios;
import com.dbagnets.backend.engine.registry.EntityIdRegistry.RegistryEntry;
import com.dbagnets.backend.engine.scenario.AggregateParams;
import com.dbagnets.backend.engine.scenario.KnnParams;
import com.dbagnets.backend.engine.scenario.RangeParams;
import com.dbagnets.backend.engine.scenario.ResultCanonicalizer;
import com.dbagnets.backend.engine.scenario.ScenarioContext;
import com.dbagnets.backend.engine.scenario.TraversalParams;
import com.dbagnets.backend.engine.schema.EmbeddingMap;
import com.dbagnets.backend.engine.schema.EmbeddingMapping;
import com.dbagnets.backend.engine.schema.LogicalEntity;
import com.dbagnets.backend.engine.timing.RecordedId;
import com.dbagnets.backend.engine.timing.TimedOperation;
import com.dbagnets.backend.domain.DatabaseEngine;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.WriteModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class MongoDriver implements EngineDriver {

    private static final String DATABASE_NAME = "benchmark";

    private final MongoClientCache clientCache;

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.MONGODB;
    }

    @Override
    public TimedOperation insert(InsertContext ctx) throws Exception {
        MongoDatabase db = resolveDb(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        return InsertOuterLoop.run(ctx, (node, rows) -> writeEntity(db, ctx, node, rows));
    }

    @Override
    public TimedOperation read(ReadContext ctx) {
        MongoDatabase db = resolveDb(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        Optional<EmbeddingMapping> embedding = findEmbedding(ctx.embeddings(), ctx.entityName());
        boolean embedded = embedding.isPresent();
        String collectionName = embedded ? embedding.get().parentEntity().toLowerCase() : ctx.entityName().toLowerCase();
        MongoCollection<Document> collection = db.getCollection(collectionName);
        String fieldName = embedded ? embedding.get().fieldName() : null;

        ReadDepth depth = ctx.readDepth() == null ? ReadDepth.NONE : ctx.readDepth();
        List<PgScenarios.TraversalLevel> chain = chainForDepth(ctx.schema(), ctx.entityName(), depth);

        SampledAccumulator acc = new SampledAccumulator(ctx.targets().size());
        long wireStart = System.nanoTime();
        for (int i = 0; i < ctx.targets().size(); i++) {
            RegistryEntry entry = ctx.targets().get(i);
            Document filter = embedded ? new Document(fieldName + "._id", entry.physicalId()) : new Document("_id", entry.physicalId());
            long start = System.nanoTime();
            Document found = collection.find(filter).first();
            long innerRows = found == null ? 0 : 1;
            if (!embedded && !chain.isEmpty() && found != null) {
                innerRows += fetchMongoDescendants(db, entry.physicalId(), chain);
            }
            acc.sample(i, System.nanoTime() - start, innerRows);
        }
        return acc.finish(System.nanoTime() - wireStart);
    }

    @Override
    public TimedOperation delete(DeleteContext ctx) {
        MongoDatabase db = resolveDb(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        Optional<EmbeddingMapping> embedding = findEmbedding(ctx.embeddings(), ctx.entityName());
        boolean embedded = embedding.isPresent();

        SampledAccumulator acc = new SampledAccumulator(ctx.targets().size());
        Map<String, List<String>> cascadeAccumulator = new LinkedHashMap<>();

        long wireStart = System.nanoTime();
        if (embedded) {
            String parentCollection = embedding.get().parentEntity().toLowerCase();
            String arrayField = embedding.get().fieldName();
            MongoCollection<Document> parentCol = db.getCollection(parentCollection);
            for (int i = 0; i < ctx.targets().size(); i++) {
                RegistryEntry entry = ctx.targets().get(i);
                long start = System.nanoTime();
                long updated = parentCol.updateMany(new Document(arrayField + "._id", entry.physicalId()), Updates.pull(arrayField, new Document("_id", entry.physicalId()))).getModifiedCount();
                acc.sample(i, System.nanoTime() - start, updated > 0 ? updated : 0);
            }
        } else {
            String collectionName = ctx.entityName().toLowerCase();
            MongoCollection<Document> collection = db.getCollection(collectionName);
            for (int i = 0; i < ctx.targets().size(); i++) {
                RegistryEntry entry = ctx.targets().get(i);
                long start = System.nanoTime();
                if (ctx.includeChildren()) {
                    cascadeChildrenBfs(db, ctx.schema(), ctx.entityName(), entry.physicalId(), cascadeAccumulator);
                }
                long deleted = collection.deleteOne(new Document("_id", entry.physicalId())).getDeletedCount();
                acc.sample(i, System.nanoTime() - start, deleted > 0 ? deleted : 0);
            }
        }
        return acc.finishWithCascade(System.nanoTime() - wireStart, cascadeAccumulator);
    }

    @Override
    public ScenarioOutcome runScenario(ScenarioContext ctx) throws Exception {
        MongoDatabase db = resolveDb(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        return ScenarioTimings.execute(() -> switch (ctx.params()) {
            case AggregateParams p -> {
                Map<String, Long> grouped = MongoScenarios.executeAggregate(db, ctx.schema(), ctx.embeddings(), p.parentEntity(), p.childEntity());
                yield ResultCanonicalizer.build(grouped, grouped.size());
            }
            case RangeParams p -> {
                long count = MongoScenarios.executeRangeCount(db, ctx.schema(), ctx.embeddings(), p.entityName(), p.attribute(), p.min(), p.max());
                yield ResultCanonicalizer.build(Map.of("count", count), count);
            }
            case TraversalParams p -> {
                List<String> ids = MongoScenarios.executeTraversal(db, ctx.schema(), p.startEntity(), p.startLogicalId(), p.depth());
                yield ResultCanonicalizer.build(ids, ids.size());
            }
            case KnnParams ignored ->
                    throw new UnsupportedOperationException(engine() + " does not support VECTOR_KNN");
        });
    }

    private List<PgScenarios.TraversalLevel> chainForDepth(com.dbagnets.backend.engine.schema.LogicalSchema schema, String entityName, ReadDepth depth) {
        if (depth == ReadDepth.NONE) return List.of();
        if (depth == ReadDepth.ONE_HOP) return PgScenarios.resolveChain(schema, entityName, 1);
        return PgScenarios.resolveChain(schema, entityName, ReadDepth.FULL_CASCADE_MAX_DEPTH);
    }

    private long fetchMongoDescendants(MongoDatabase db, Object rootId, List<PgScenarios.TraversalLevel> chain) {
        return FrontierBfs.descend(chain, rootId, (level, frontier, nextFrontier) -> {
            MongoCollection<Document> collection = db.getCollection(level.childEntity().toLowerCase());
            Document filter = new Document(level.fkColumn().toLowerCase(), new Document("$in", frontier));
            long count = 0L;
            for (Document doc : collection.find(filter).projection(new Document("_id", 1))) {
                Object id = doc.get("_id");
                if (id == null) continue;
                count++;
                nextFrontier.add(id);
            }
            return count;
        });
    }

    private void cascadeChildrenBfs(MongoDatabase db, com.dbagnets.backend.engine.schema.LogicalSchema schema, String rootEntity, Object rootId, Map<String, List<String>> accumulator) {
        CascadeBfsState state = new CascadeBfsState(rootEntity, rootId, 16);
        while (state.hasNext()) {
            String cur = state.poll();
            if (!state.visit(cur)) continue;
            List<Object> curIds = state.idsFor(cur);
            if (curIds == null || curIds.isEmpty()) continue;

            for (var rel : schema.relationships()) {
                if (!rel.parentEntity().equalsIgnoreCase(cur)) continue;
                String childName = rel.childEntity();
                if (childName.equalsIgnoreCase(cur)) continue;
                String fkCol = resolveFkColumn(rel, schema);
                if (fkCol == null) continue;
                MongoCollection<Document> childCol = db.getCollection(childName.toLowerCase());
                List<Object> childIds = new ArrayList<>();
                try {
                    for (Document d : childCol.find(new Document(fkCol, new Document("$in", curIds))).projection(new Document("_id", 1))) {
                        Object id = d.get("_id");
                        if (id != null) childIds.add(id);
                    }
                } catch (Exception ex) {
                    log.debug("Mongo cascade scan {} → {} failed: {}", cur, childName, ex.getMessage());
                    continue;
                }
                if (!childIds.isEmpty()) state.addChildren(childName, childIds);
            }
        }

        for (String entityName : state.reversedEntityOrder()) {
            if (entityName.equalsIgnoreCase(rootEntity)) continue;
            List<Object> ids = state.idsFor(entityName);
            if (ids == null || ids.isEmpty()) continue;
            try {
                db.getCollection(entityName.toLowerCase()).deleteMany(new Document("_id", new Document("$in", ids)));
                accumulator.computeIfAbsent(entityName, k -> new ArrayList<>()).addAll(ids.stream().map(String::valueOf).toList());
            } catch (Exception ex) {
                log.warn("Mongo cascade delete failed for {}: {}", entityName, ex.getMessage());
            }
        }
    }

    private String resolveFkColumn(com.dbagnets.backend.engine.schema.LogicalRelationship rel, com.dbagnets.backend.engine.schema.LogicalSchema schema) {
        String declared = rel.fkColumnInChild();
        if (declared != null && !declared.isBlank()) return declared;
        var parent = schema.findEntity(rel.parentEntity()).orElse(null);
        if (parent == null) return null;
        var parentPk = parent.primaryKey().orElse(null);
        if (parentPk == null) return null;
        var child = schema.findEntity(rel.childEntity()).orElse(null);
        if (child == null) return null;
        return child.attributes().stream().anyMatch(a -> a.name().equalsIgnoreCase(parentPk.name())) ? parentPk.name() : null;
    }

    private EntityOutcome writeEntity(MongoDatabase db, InsertContext ctx, CascadeNode node, List<GeneratedRow> rows) throws Exception {
        Optional<EmbeddingMapping> embedding = findEmbedding(ctx.embeddings(), node.entityName());
        return embedding.isPresent()
                ? embedIntoParent(db, ctx, node, rows, embedding.get())
                : insertStandalone(db, ctx, node, rows);
    }

    private EntityOutcome insertStandalone(MongoDatabase db, InsertContext ctx, CascadeNode node, List<GeneratedRow> rows) throws Exception {
        LogicalEntity entity = ctx.schema().requireEntity(node.entityName());
        String collectionName = node.entityName().toLowerCase();
        MongoCollection<Document> collection = db.getCollection(collectionName);
        InsertManyOptions options = new InsertManyOptions().ordered(false);

        BulkInsertLoop.Config config = new BulkInsertLoop.Config(
                BatchSizes.effective(ctx, 10_000),
                engine(),
                true,
                null,
                "Mongo conflict on {} batch {}/{}: {}",
                node.entityName());
        return BulkInsertLoop.run(ctx, node, rows, config, (slice, batchIndex, totalBatches) -> {
            List<Document> docs = new ArrayList<>(slice.size());
            for (GeneratedRow row : slice) {
                docs.add(toDocument(entity, row));
            }
            collection.insertMany(docs, options);
            return slice.size();
        });
    }

    private EntityOutcome embedIntoParent(MongoDatabase db, InsertContext ctx, CascadeNode node, List<GeneratedRow> rows, EmbeddingMapping mapping) {
        EntityOutcome outcome = new EntityOutcome();
        String parentCollection = mapping.parentEntity().toLowerCase();
        String arrayField = mapping.fieldName();
        MongoCollection<Document> parentCol = db.getCollection(parentCollection);

        Map<String, List<Document>> groupedByParent = groupChildrenByParent(ctx, node, rows);
        if (groupedByParent.isEmpty()) {
            return outcome;
        }

        List<WriteModel<Document>> updates = new ArrayList<>(groupedByParent.size());
        for (Map.Entry<String, List<Document>> entry : groupedByParent.entrySet()) {
            updates.add(new UpdateOneModel<>(new Document("_id", entry.getKey()), Updates.pushEach(arrayField, entry.getValue())));
        }
        try {
            long start = System.nanoTime();
            parentCol.bulkWrite(updates);
            outcome.dbTimeNs += System.nanoTime() - start;
            outcome.rowsAffected += rows.size();
            rows.forEach(r -> outcome.recordedIds.add(new RecordedId(node.entityName(), r.logicalId(), r.logicalId())));
        } catch (Exception ex) {
            if (ConflictDetector.isConflict(engine(), ex)) {
                outcome.conflicts += rows.size();
                log.warn("Mongo embed conflict on {} -> {}: {}", node.entityName(), parentCollection, ex.getMessage());
            } else {
                throw ex;
            }
        }
        ctx.progress().onBatch(node.entityName(), 1, 1, rows.size(), rows.size());
        return outcome;
    }

    private Map<String, List<Document>> groupChildrenByParent(InsertContext ctx, CascadeNode node, List<GeneratedRow> rows) {
        Map<String, List<Document>> grouped = new LinkedHashMap<>();
        String parentFkColumn = node.incomingFromParents().isEmpty() ? null : node.incomingFromParents().getFirst().fkColumnInChild();
        if (parentFkColumn == null) {
            return grouped;
        }
        LogicalEntity entity = ctx.schema().requireEntity(node.entityName());
        for (GeneratedRow row : rows) {
            Object parentRef = row.get(parentFkColumn);
            if (parentRef == null) continue;
            grouped.computeIfAbsent(parentRef.toString(), k -> new ArrayList<>()).add(toDocument(entity, row));
        }
        return grouped;
    }

    private Document toDocument(LogicalEntity entity, GeneratedRow row) {
        Document doc = new Document();
        entity.primaryKey().ifPresent(pk -> doc.put("_id", row.get(pk.name())));
        for (Map.Entry<String, Object> entry : row.values().entrySet()) {
            doc.put(entry.getKey(), DriverValues.serialize(entry.getValue()));
        }
        return doc;
    }

    private MongoDatabase resolveDb(String databaseId, String host, int port) {
        return clientCache.get(databaseId, host, port).getDatabase(DATABASE_NAME);
    }

    private static Optional<EmbeddingMapping> findEmbedding(EmbeddingMap embeddings, String entityName) {
        return embeddings.lookup(entityName).filter(EmbeddingMapping::isEmbedded);
    }
}
