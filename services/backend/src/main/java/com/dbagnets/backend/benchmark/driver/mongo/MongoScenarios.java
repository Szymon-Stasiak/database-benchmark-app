package com.dbagnets.backend.benchmark.driver.mongo;

import com.dbagnets.backend.benchmark.driver.pg.PgScenarios;
import com.dbagnets.backend.benchmark.schema.EmbeddingMap;
import com.dbagnets.backend.benchmark.schema.EmbeddingMapping;
import com.dbagnets.backend.benchmark.schema.LogicalAttribute;
import com.dbagnets.backend.benchmark.schema.LogicalEntity;
import com.dbagnets.backend.benchmark.schema.LogicalRelationship;
import com.dbagnets.backend.benchmark.schema.LogicalSchema;
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

    public static Map<String, Long> executeAggregate(MongoDatabase db,
                                                       LogicalSchema schema,
                                                       EmbeddingMap embeddings,
                                                       String parentEntity,
                                                       String childEntity) {
        LogicalRelationship rel = findRelationship(schema, parentEntity, childEntity);
        Optional<EmbeddingMapping> childMapping = embeddings.lookup(childEntity);
        boolean childEmbedded = childMapping.isPresent() && childMapping.get().isEmbedded();

        if (childEmbedded) {
            return executeEmbeddedAggregate(db, childMapping.get());
        }
        return executeStandaloneAggregate(db, childEntity, rel.fkColumnInChild());
    }

    private static Map<String, Long> executeStandaloneAggregate(MongoDatabase db,
                                                                  String childEntity,
                                                                  String fkColumn) {
        String collectionName = childEntity.toLowerCase();
        String groupField = fkColumn.toLowerCase();
        MongoCollection<Document> collection = db.getCollection(collectionName);
        AggregateIterable<Document> pipeline = collection.aggregate(Arrays.asList(
                new Document("$match", new Document(groupField, new Document("$ne", null))),
                new Document("$group", new Document("_id", "$" + groupField)
                        .append("cnt", new Document("$sum", 1))),
                new Document("$sort", new Document("_id", 1))
        ));
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

    private static Map<String, Long> executeEmbeddedAggregate(MongoDatabase db, EmbeddingMapping mapping) {
        String parentCollection = mapping.parentEntity().toLowerCase();
        String arrayField = mapping.fieldName();
        MongoCollection<Document> parents = db.getCollection(parentCollection);
        AggregateIterable<Document> pipeline = parents.aggregate(Arrays.asList(
                new Document("$project", new Document("_id", 1)
                        .append("cnt", new Document("$size", new Document("$ifNull",
                                Arrays.asList("$" + arrayField, Collections.emptyList()))))),
                new Document("$match", new Document("cnt", new Document("$gt", 0))),
                new Document("$sort", new Document("_id", 1))
        ));
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

    public static long executeRangeCount(MongoDatabase db,
                                          LogicalSchema schema,
                                          EmbeddingMap embeddings,
                                          String entityName,
                                          String attribute,
                                          double min,
                                          double max) {
        LogicalEntity entity = schema.requireEntity(entityName);
        LogicalAttribute attr = entity.findAttribute(attribute)
                .orElseThrow(() -> new IllegalArgumentException("Attribute " + attribute + " not found"));
        if (!isNumericLike(attr)) {
            throw new IllegalArgumentException("Attribute " + attribute + " is not numeric — type " + attr.dataType());
        }
        Optional<EmbeddingMapping> mapping = embeddings.lookup(entityName);
        boolean embedded = mapping.isPresent() && mapping.get().isEmbedded();
        String attrName = attr.name().toLowerCase();

        if (embedded) {
            String parentCollection = mapping.get().parentEntity().toLowerCase();
            String arrayField = mapping.get().fieldName();
            MongoCollection<Document> parents = db.getCollection(parentCollection);
            AggregateIterable<Document> pipeline = parents.aggregate(Arrays.asList(
                    new Document("$unwind", "$" + arrayField),
                    new Document("$match", new Document(arrayField + "." + attrName,
                            new Document("$gte", min).append("$lte", max))),
                    new Document("$count", "cnt")
            ));
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

    public static List<String> executeTraversal(MongoDatabase db,
                                                  LogicalSchema schema,
                                                  String startEntity,
                                                  String startLogicalId,
                                                  int depth) {
        List<PgScenarios.TraversalLevel> chain = PgScenarios.resolveChain(schema, startEntity, depth);
        if (chain.isEmpty()) return List.of();

        java.util.Set<String> reachable = new java.util.TreeSet<>();
        List<Object> frontier = List.of((Object) startLogicalId);
        for (PgScenarios.TraversalLevel level : chain) {
            if (frontier.isEmpty()) break;
            MongoCollection<Document> collection = db.getCollection(level.childEntity().toLowerCase());
            Document filter = new Document(level.fkColumn().toLowerCase(),
                    new Document("$in", frontier));
            List<Object> nextFrontier = new ArrayList<>();
            for (Document doc : collection.find(filter).projection(new Document("_id", 1))) {
                Object id = doc.get("_id");
                if (id == null) continue;
                reachable.add(String.valueOf(id));
                nextFrontier.add(id);
            }
            frontier = nextFrontier;
        }
        return new ArrayList<>(reachable);
    }

    private static LogicalRelationship findRelationship(LogicalSchema schema, String parent, String child) {
        return schema.relationships().stream()
                .filter(r -> r.parentEntity().equalsIgnoreCase(parent)
                        && r.childEntity().equalsIgnoreCase(child))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No relationship found between parent=" + parent + " and child=" + child));
    }

    private static boolean isNumericLike(LogicalAttribute attr) {
        return switch (attr.dataType()) {
            case INTEGER, BIGINT, FLOAT, DOUBLE, DECIMAL, DATE, TIMESTAMP -> true;
            default -> false;
        };
    }
}
