package com.dbagnets.backend.engine.driver.mongo;

import com.dbagnets.backend.engine.driver.pg.PgScenarios;
import com.dbagnets.backend.engine.scenario.ScenarioSupport;
import com.dbagnets.backend.engine.schema.EmbeddingMap;
import com.dbagnets.backend.engine.schema.EmbeddingMapping;
import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalEntity;
import com.dbagnets.backend.engine.schema.LogicalRelationship;
import com.dbagnets.backend.engine.schema.LogicalSchema;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public final class MongoScenarios {

    private MongoScenarios() {
    }

    public static Map<String, Long> executeAggregate(MongoDatabase db, LogicalSchema schema, EmbeddingMap embeddings, String parentEntity, String childEntity) {
        LogicalRelationship rel = ScenarioSupport.findRelationship(schema, parentEntity, childEntity);
        Optional<EmbeddingMapping> childMapping = embeddings.lookup(childEntity);
        boolean childEmbedded = childMapping.isPresent() && childMapping.get().isEmbedded();

        if (childEmbedded) {
            return executeEmbeddedAggregate(db, childMapping.get());
        }
        return executeStandaloneAggregate(db, childEntity, rel.fkColumnInChild());
    }

    private static Map<String, Long> executeStandaloneAggregate(MongoDatabase db, String childEntity, String fkColumn) {
        String collectionName = childEntity.toLowerCase();
        String groupField = fkColumn.toLowerCase();
        MongoCollection<Document> collection = db.getCollection(collectionName);
        AggregateIterable<Document> pipeline = collection.aggregate(Arrays.asList(new Document("$match", new Document(groupField, new Document("$ne", null))), new Document("$group", new Document("_id", "$" + groupField).append("cnt", new Document("$sum", 1))), new Document("$sort", new Document("_id", 1))));
        return collectAggregateCounts(pipeline);
    }

    private static Map<String, Long> executeEmbeddedAggregate(MongoDatabase db, EmbeddingMapping mapping) {
        String parentCollection = mapping.parentEntity().toLowerCase();
        String arrayField = mapping.fieldName();
        MongoCollection<Document> parents = db.getCollection(parentCollection);
        AggregateIterable<Document> pipeline = parents.aggregate(Arrays.asList(new Document("$project", new Document("_id", 1).append("cnt", new Document("$size", new Document("$ifNull", Arrays.asList("$" + arrayField, Collections.emptyList()))))), new Document("$match", new Document("cnt", new Document("$gt", 0))), new Document("$sort", new Document("_id", 1))));
        return collectAggregateCounts(pipeline);
    }

    public static long executeRangeCount(MongoDatabase db, LogicalSchema schema, EmbeddingMap embeddings, String entityName, String attribute, double min, double max) {
        LogicalEntity entity = schema.requireEntity(entityName);
        LogicalAttribute attr = entity.findAttribute(attribute).orElseThrow(() -> new IllegalArgumentException("Attribute " + attribute + " not found"));
        if (!ScenarioSupport.isNumericLike(attr)) {
            throw new IllegalArgumentException("Attribute " + attribute + " is not numeric — type " + attr.dataType());
        }
        Optional<EmbeddingMapping> mapping = embeddings.lookup(entityName);
        boolean embedded = mapping.isPresent() && mapping.get().isEmbedded();
        String attrName = attr.name().toLowerCase();

        if (embedded) {
            String parentCollection = mapping.get().parentEntity().toLowerCase();
            String arrayField = mapping.get().fieldName();
            MongoCollection<Document> parents = db.getCollection(parentCollection);
            AggregateIterable<Document> pipeline = parents.aggregate(Arrays.asList(new Document("$unwind", "$" + arrayField), new Document("$match", new Document(arrayField + "." + attrName, new Document("$gte", min).append("$lte", max))), new Document("$count", "cnt")));
            for (Document doc : pipeline) {
                Number cnt = doc.get("cnt", Number.class);
                if (cnt != null) return cnt.longValue();
            }
            return 0L;
        }
        MongoCollection<Document> collection = db.getCollection(entity.name().toLowerCase());
        Document filter = new Document(attrName, new Document("$gte", min).append("$lte", max));
        return collection.countDocuments(filter);
    }

    public static List<String> executeTraversal(MongoDatabase db, LogicalSchema schema, String startEntity, String startLogicalId, int depth) {
        List<PgScenarios.TraversalLevel> chain = PgScenarios.resolveChain(schema, startEntity, depth);
        if (chain.isEmpty()) return List.of();

        java.util.Set<String> reachable = new java.util.TreeSet<>();
        com.dbagnets.backend.engine.driver.FrontierBfs.descend(chain, startLogicalId, (level, frontier, nextFrontier) -> {
            MongoCollection<Document> collection = db.getCollection(level.childEntity().toLowerCase());
            Document filter = new Document(level.fkColumn().toLowerCase(), new Document("$in", frontier));
            for (Document doc : collection.find(filter).projection(new Document("_id", 1))) {
                Object id = doc.get("_id");
                if (id == null) continue;
                reachable.add(String.valueOf(id));
                nextFrontier.add(id);
            }
            return 0L;
        });
        return new ArrayList<>(reachable);
    }

    private static Map<String, Long> collectAggregateCounts(AggregateIterable<Document> pipeline) {
        Map<String, Long> result = new TreeMap<>();
        for (Document doc : pipeline) {
            Object key = doc.get("_id");
            Number cnt = doc.get("cnt", Number.class);
            if (key != null && cnt != null) {
                result.put(String.valueOf(key), cnt.longValue());
            }
        }
        return result;
    }
}
