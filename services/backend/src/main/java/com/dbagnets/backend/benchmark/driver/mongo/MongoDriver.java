package com.dbagnets.backend.benchmark.driver.mongo;

import com.dbagnets.backend.benchmark.cascade.CascadeNode;
import com.dbagnets.backend.benchmark.datagen.GeneratedRow;
import com.dbagnets.backend.benchmark.driver.ConflictDetector;
import com.dbagnets.backend.benchmark.driver.DeleteContext;
import com.dbagnets.backend.benchmark.driver.EngineDriver;
import com.dbagnets.backend.benchmark.driver.InsertContext;
import com.dbagnets.backend.benchmark.driver.InsertMode;
import com.dbagnets.backend.benchmark.driver.ReadContext;
import com.dbagnets.backend.benchmark.registry.EntityIdRegistry.RegistryEntry;
import com.dbagnets.backend.benchmark.schema.EmbeddingMap;
import com.dbagnets.backend.benchmark.schema.EmbeddingMapping;
import com.dbagnets.backend.benchmark.schema.LogicalEntity;
import com.dbagnets.backend.benchmark.timing.RecordedId;
import com.dbagnets.backend.benchmark.timing.TimedOperation;
import com.dbagnets.backend.entity.DatabaseEngine;
import com.mongodb.client.MongoClient;
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
    private static final String PROFILE_COLLECTION = "system.profile";

    private final MongoClientCache clientCache;

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.MONGODB;
    }

    @Override
    public TimedOperation insert(InsertContext ctx) {
        MongoClient client = clientCache.get(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        MongoDatabase db = client.getDatabase(DATABASE_NAME);

        long totalDbTimeNs = 0L;
        long totalRowsAffected = 0L;
        int totalConflicts = 0;
        List<RecordedId> recordedIds = new ArrayList<>();

        prepareProfiler(db);
        long wireStart = System.nanoTime();
        try {
            for (CascadeNode node : ctx.plan().nodesInInsertOrder()) {
                List<GeneratedRow> rows = ctx.rowsByEntity().get(node.entityName());
                if (rows == null || rows.isEmpty()) continue;
                EntityWriteOutcome outcome = writeEntity(db, ctx, node, rows);
                totalDbTimeNs += outcome.dbTimeNs;
                totalRowsAffected += outcome.rowsAffected;
                totalConflicts += outcome.conflicts;
                recordedIds.addAll(outcome.recordedIds);
                ctx.progress().onEntityFinished(node.entityName());
            }
        } finally {
            stopProfiler(db);
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
        MongoClient client = clientCache.get(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        MongoDatabase db = client.getDatabase(DATABASE_NAME);
        EmbeddingMap embeddings = ctx.embeddings();
        Optional<EmbeddingMapping> mapping = embeddings.lookup(ctx.entityName());
        boolean embedded = mapping.isPresent() && mapping.get().isEmbedded();
        String collectionName = embedded ? mapping.get().parentEntity().toLowerCase() : ctx.entityName().toLowerCase();
        MongoCollection<Document> collection = db.getCollection(collectionName);
        String fieldName = embedded ? mapping.get().fieldName() : null;

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsRead = 0L;

        long wireStart = System.nanoTime();
        for (int i = 0; i < ctx.targets().size(); i++) {
            RegistryEntry entry = ctx.targets().get(i);
            Document filter = embedded
                    ? new Document(fieldName + "._id", entry.physicalId())
                    : new Document("_id", entry.physicalId());
            long sampleNs = explainFind(collection, filter);
            samples[i] = sampleNs;
            totalDbTimeNs += sampleNs;
            Document found = collection.find(filter).first();
            if (found != null) rowsRead++;
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
        MongoClient client = clientCache.get(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        MongoDatabase db = client.getDatabase(DATABASE_NAME);
        EmbeddingMap embeddings = ctx.embeddings();
        Optional<EmbeddingMapping> mapping = embeddings.lookup(ctx.entityName());
        boolean embedded = mapping.isPresent() && mapping.get().isEmbedded();

        long totalDbTimeNs = 0L;
        long rowsAffected = 0L;
        long[] samples = new long[ctx.targets().size()];

        prepareProfiler(db);
        long wireStart = System.nanoTime();
        try {
            if (embedded) {
                String parentCollection = mapping.get().parentEntity().toLowerCase();
                String arrayField = mapping.get().fieldName();
                MongoCollection<Document> parentCol = db.getCollection(parentCollection);
                for (int i = 0; i < ctx.targets().size(); i++) {
                    RegistryEntry entry = ctx.targets().get(i);
                    long before = drainProfilerForCollection(db, parentCollection);
                    totalDbTimeNs += before;
                    long updated = parentCol.updateMany(
                            new Document(arrayField + "._id", entry.physicalId()),
                            Updates.pull(arrayField, new Document("_id", entry.physicalId()))
                    ).getModifiedCount();
                    long sample = drainProfilerForCollection(db, parentCollection);
                    samples[i] = sample;
                    totalDbTimeNs += sample;
                    if (updated > 0) rowsAffected += updated;
                }
            } else {
                String collectionName = ctx.entityName().toLowerCase();
                MongoCollection<Document> collection = db.getCollection(collectionName);
                for (int i = 0; i < ctx.targets().size(); i++) {
                    RegistryEntry entry = ctx.targets().get(i);
                    long deleted = collection.deleteOne(new Document("_id", entry.physicalId())).getDeletedCount();
                    long sample = drainProfilerForCollection(db, collectionName);
                    samples[i] = sample;
                    totalDbTimeNs += sample;
                    if (deleted > 0) rowsAffected += deleted;
                }
            }
        } finally {
            stopProfiler(db);
        }
        long wireTimeNs = System.nanoTime() - wireStart;

        return TimedOperation.builder()
                .dbTimeNs(totalDbTimeNs)
                .wireTimeNs(wireTimeNs)
                .rowsAffected(rowsAffected)
                .sampleDbTimeNs(samples)
                .build();
    }

    private long explainFind(MongoCollection<Document> collection, Document filter) {
        try {
            Document explainResult = collection.find(filter).explain();
            Document execStats = explainResult.get("executionStats", Document.class);
            if (execStats != null) {
                Number millis = execStats.get("executionTimeMillis", Number.class);
                Number micros = execStats.get("executionTimeMillisEstimate", Number.class);
                if (millis != null && millis.longValue() > 0) {
                    return millis.longValue() * 1_000_000L;
                }
                if (micros != null) {
                    return micros.longValue() * 1_000_000L;
                }
            }
        } catch (Exception e) {
            log.debug("Mongo explain failed: {}", e.getMessage());
        }
        return 0L;
    }

    private EntityWriteOutcome writeEntity(MongoDatabase db,
                                            InsertContext ctx,
                                            CascadeNode node,
                                            List<GeneratedRow> rows) {
        EmbeddingMap embeddings = ctx.embeddings();
        Optional<EmbeddingMapping> mapping = embeddings.lookup(node.entityName());
        if (mapping.isPresent() && mapping.get().isEmbedded()) {
            return embedIntoParent(db, ctx, node, rows, mapping.get());
        }
        return insertStandalone(db, ctx, node, rows);
    }

    private EntityWriteOutcome insertStandalone(MongoDatabase db,
                                                 InsertContext ctx,
                                                 CascadeNode node,
                                                 List<GeneratedRow> rows) {
        EntityWriteOutcome outcome = new EntityWriteOutcome();
        LogicalEntity entity = ctx.schema().requireEntity(node.entityName());
        String collectionName = node.entityName().toLowerCase();
        MongoCollection<Document> collection = db.getCollection(collectionName);

        int batchSize = effectiveBatchSize(ctx);
        int totalBatches = Math.max(1, (int) Math.ceil((double) rows.size() / batchSize));
        InsertManyOptions options = new InsertManyOptions().ordered(false);

        int batchIndex = 0;
        for (int from = 0; from < rows.size(); from += batchSize) {
            int to = Math.min(from + batchSize, rows.size());
            List<GeneratedRow> slice = rows.subList(from, to);
            List<Document> docs = new ArrayList<>(slice.size());
            for (GeneratedRow row : slice) {
                docs.add(toDocument(entity, row));
            }
            try {
                collection.insertMany(docs, options);
                outcome.rowsAffected += slice.size();
                slice.forEach(r -> outcome.recordedIds.add(new RecordedId(node.entityName(), r.logicalId(), r.logicalId())));
            } catch (Exception ex) {
                if (ConflictDetector.isConflict(engine(), ex)) {
                    outcome.conflicts += slice.size();
                    log.warn("Mongo conflict on {} batch {}/{}: {}", node.entityName(), batchIndex, totalBatches, ex.getMessage());
                } else {
                    throw ex;
                }
            }
            batchIndex++;
            ctx.progress().onBatch(node.entityName(), batchIndex, totalBatches, to, rows.size());
        }
        outcome.dbTimeNs += drainProfilerForCollection(db, collectionName);
        return outcome;
    }

    private EntityWriteOutcome embedIntoParent(MongoDatabase db,
                                                InsertContext ctx,
                                                CascadeNode node,
                                                List<GeneratedRow> rows,
                                                EmbeddingMapping mapping) {
        EntityWriteOutcome outcome = new EntityWriteOutcome();
        String parentCollection = mapping.parentEntity().toLowerCase();
        String arrayField = mapping.fieldName();
        MongoCollection<Document> parentCol = db.getCollection(parentCollection);

        Map<String, List<Document>> groupedByParent = groupChildrenByParent(ctx, node, rows);
        if (groupedByParent.isEmpty()) {
            return outcome;
        }

        List<WriteModel<Document>> updates = new ArrayList<>(groupedByParent.size());
        for (Map.Entry<String, List<Document>> entry : groupedByParent.entrySet()) {
            updates.add(new UpdateOneModel<>(
                    new Document("_id", entry.getKey()),
                    Updates.pushEach(arrayField, entry.getValue())));
        }
        try {
            parentCol.bulkWrite(updates);
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
        outcome.dbTimeNs += drainProfilerForCollection(db, parentCollection);
        ctx.progress().onBatch(node.entityName(), 1, 1, rows.size(), rows.size());
        return outcome;
    }

    private Map<String, List<Document>> groupChildrenByParent(InsertContext ctx, CascadeNode node, List<GeneratedRow> rows) {
        Map<String, List<Document>> grouped = new LinkedHashMap<>();
        String parentFkColumn = node.incomingFromParents().isEmpty()
                ? null
                : node.incomingFromParents().get(0).fkColumnInChild();
        if (parentFkColumn == null) {
            return grouped;
        }
        LogicalEntity entity = ctx.schema().requireEntity(node.entityName());
        for (GeneratedRow row : rows) {
            Object parentRef = row.get(parentFkColumn);
            if (parentRef == null) continue;
            grouped.computeIfAbsent(parentRef.toString(), k -> new ArrayList<>())
                    .add(toDocument(entity, row));
        }
        return grouped;
    }

    private Document toDocument(LogicalEntity entity, GeneratedRow row) {
        Document doc = new Document();
        entity.primaryKey().ifPresent(pk -> doc.put("_id", row.get(pk.name())));
        for (Map.Entry<String, Object> entry : row.values().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof float[] arr) {
                List<Double> list = new ArrayList<>(arr.length);
                for (float f : arr) list.add((double) f);
                doc.put(entry.getKey(), list);
            } else {
                doc.put(entry.getKey(), value);
            }
        }
        return doc;
    }

    private void prepareProfiler(MongoDatabase db) {
        try {
            db.runCommand(new Document("profile", 0));
            db.getCollection(PROFILE_COLLECTION).drop();
            db.runCommand(new Document("profile", 2).append("slowms", 0));
        } catch (Exception e) {
            log.debug("Mongo profiler setup failed: {}", e.getMessage());
        }
    }

    private void stopProfiler(MongoDatabase db) {
        try {
            db.runCommand(new Document("profile", 0));
        } catch (Exception e) {
            log.debug("Mongo profiler shutdown failed: {}", e.getMessage());
        }
    }

    private long drainProfilerForCollection(MongoDatabase db, String collectionName) {
        try {
            String ns = DATABASE_NAME + "." + collectionName;
            long totalNanos = 0L;
            MongoCollection<Document> profile = db.getCollection(PROFILE_COLLECTION);
            for (Document doc : profile.find(new Document("ns", ns))) {
                Number millis = doc.get("durationMillis", Number.class);
                if (millis != null) {
                    totalNanos += millis.longValue() * 1_000_000L;
                }
            }
            profile.deleteMany(new Document("ns", ns));
            return totalNanos;
        } catch (Exception e) {
            log.debug("Mongo profiler drain failed: {}", e.getMessage());
            return 0L;
        }
    }

    private int effectiveBatchSize(InsertContext ctx) {
        return switch (ctx.mode()) {
            case SINGLE -> 1;
            case BATCH -> Math.max(1, ctx.batchSize());
            case BULK -> Math.max(1, ctx.batchSize() > 0 ? ctx.batchSize() : 10_000);
        };
    }

    private static final class EntityWriteOutcome {
        long dbTimeNs;
        long rowsAffected;
        int conflicts;
        List<RecordedId> recordedIds = new ArrayList<>();
    }
}
