package com.dbagnets.backend.engine.driver.dynamo;

import com.dbagnets.backend.engine.cascade.CascadeNode;
import com.dbagnets.backend.engine.datagen.GeneratedRow;
import com.dbagnets.backend.engine.driver.DeleteContext;
import com.dbagnets.backend.engine.driver.EngineDriver;
import com.dbagnets.backend.engine.driver.InsertContext;
import com.dbagnets.backend.engine.driver.ReadContext;
import com.dbagnets.backend.engine.registry.EntityIdRegistry.RegistryEntry;
import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalEntity;
import com.dbagnets.backend.engine.timing.RecordedId;
import com.dbagnets.backend.engine.timing.TimedOperation;
import com.dbagnets.backend.domain.DatabaseEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class DynamodbDriver implements EngineDriver {

    private static final int BATCH_LIMIT = 25;

    private final DynamodbClientCache clientCache;
    private final Set<String> ensuredTables = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.DYNAMODB;
    }

    @Override
    public TimedOperation insert(InsertContext ctx) {
        DynamoDbClient client = clientCache.get(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());

        long totalDbTimeNs = 0L;
        long totalRowsAffected = 0L;
        List<RecordedId> recordedIds = new ArrayList<>();

        long wireStart = System.nanoTime();
        for (CascadeNode node : ctx.plan().nodesInInsertOrder()) {
            List<GeneratedRow> rows = ctx.rowsByEntity().get(node.entityName());
            if (rows == null || rows.isEmpty()) continue;
            LogicalEntity entity = ctx.schema().requireEntity(node.entityName());
            String table = node.entityName().toLowerCase();
            ensureTable(client, table, entity);
            EntityOutcome outcome = bulkInsert(client, ctx, node, entity, table, rows);
            totalDbTimeNs += outcome.dbTimeNs;
            totalRowsAffected += outcome.rowsAffected;
            recordedIds.addAll(outcome.recordedIds);
            ctx.progress().onEntityFinished(node.entityName());
        }
        long wireTimeNs = System.nanoTime() - wireStart;

        return TimedOperation.builder()
                .dbTimeNs(totalDbTimeNs)
                .wireTimeNs(wireTimeNs)
                .rowsAffected(totalRowsAffected)
                .conflictsSkipped(0)
                .recordedIds(recordedIds)
                .build();
    }

    @Override
    public TimedOperation read(ReadContext ctx) {
        DynamoDbClient client = clientCache.get(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        LogicalEntity entity = ctx.schema().requireEntity(ctx.entityName());
        String pkName = entity.primaryKey()
                .orElseThrow(() -> new IllegalStateException("Entity missing PK: " + entity.name()))
                .name();
        String table = ctx.entityName().toLowerCase();

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsRead = 0L;

        long wireStart = System.nanoTime();
        for (int i = 0; i < ctx.targets().size(); i++) {
            RegistryEntry entry = ctx.targets().get(i);
            try {
                long start = System.nanoTime();
                var resp = client.getItem(GetItemRequest.builder()
                        .tableName(table)
                        .key(Map.of(pkName, AttributeValue.fromS(String.valueOf(entry.physicalId()))))
                        .build());
                long sampleNs = System.nanoTime() - start;
                samples[i] = sampleNs;
                totalDbTimeNs += sampleNs;
                if (resp.hasItem() && !resp.item().isEmpty()) rowsRead++;
            } catch (Exception ex) {
                log.warn("DynamoDB read failed {}/{}: {}", table, entry.physicalId(), ex.getMessage());
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
        DynamoDbClient client = clientCache.get(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        LogicalEntity entity = ctx.schema().requireEntity(ctx.entityName());
        String pkName = entity.primaryKey()
                .orElseThrow(() -> new IllegalStateException("Entity missing PK: " + entity.name()))
                .name();
        String table = ctx.entityName().toLowerCase();

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsAffected = 0L;

        long wireStart = System.nanoTime();
        for (int i = 0; i < ctx.targets().size(); i++) {
            RegistryEntry entry = ctx.targets().get(i);
            try {
                long start = System.nanoTime();
                client.deleteItem(DeleteItemRequest.builder()
                        .tableName(table)
                        .key(Map.of(pkName, AttributeValue.fromS(String.valueOf(entry.physicalId()))))
                        .build());
                long sampleNs = System.nanoTime() - start;
                samples[i] = sampleNs;
                totalDbTimeNs += sampleNs;
                rowsAffected++;
            } catch (Exception ex) {
                log.warn("DynamoDB delete failed {}/{}: {}", table, entry.physicalId(), ex.getMessage());
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

    private void ensureTable(DynamoDbClient client, String table, LogicalEntity entity) {
        if (!ensuredTables.add(table)) return;
        try {
            client.describeTable(DescribeTableRequest.builder().tableName(table).build());
            return;
        } catch (Exception ignore) {
        }
        String pkName = entity.primaryKey()
                .orElseThrow(() -> new IllegalStateException("Entity missing PK: " + entity.name()))
                .name();
        try {
            client.createTable(CreateTableRequest.builder()
                    .tableName(table)
                    .keySchema(KeySchemaElement.builder().attributeName(pkName).keyType(KeyType.HASH).build())
                    .attributeDefinitions(AttributeDefinition.builder()
                            .attributeName(pkName)
                            .attributeType(ScalarAttributeType.S)
                            .build())
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build());
        } catch (Exception ex) {
            log.warn("DynamoDB createTable failed for {}: {}", table, ex.getMessage());
        }
    }

    private EntityOutcome bulkInsert(DynamoDbClient client,
                                     InsertContext ctx,
                                     CascadeNode node,
                                     LogicalEntity entity,
                                     String table,
                                     List<GeneratedRow> rows) {
        EntityOutcome outcome = new EntityOutcome();
        int batchSize = Math.min(BATCH_LIMIT, effectiveBatchSize(ctx));
        int totalBatches = Math.max(1, (int) Math.ceil((double) rows.size() / batchSize));
        int batchIndex = 0;

        for (int from = 0; from < rows.size(); from += batchSize) {
            int to = Math.min(from + batchSize, rows.size());
            List<GeneratedRow> slice = rows.subList(from, to);
            List<WriteRequest> writes = new ArrayList<>(slice.size());
            for (GeneratedRow row : slice) {
                writes.add(WriteRequest.builder()
                        .putRequest(PutRequest.builder().item(toItem(entity, row)).build())
                        .build());
            }
            try {
                long start = System.nanoTime();
                client.batchWriteItem(BatchWriteItemRequest.builder()
                        .requestItems(Map.of(table, writes))
                        .build());
                outcome.dbTimeNs += System.nanoTime() - start;
                outcome.rowsAffected += slice.size();
                slice.forEach(r -> outcome.recordedIds.add(new RecordedId(node.entityName(), r.logicalId(), r.logicalId())));
            } catch (Exception ex) {
                log.warn("DynamoDB batchWrite failed on {} batch {}: {}", table, batchIndex, ex.getMessage());
            }
            batchIndex++;
            ctx.progress().onBatch(node.entityName(), batchIndex, totalBatches, to, rows.size());
        }
        return outcome;
    }

    private Map<String, AttributeValue> toItem(LogicalEntity entity, GeneratedRow row) {
        Map<String, AttributeValue> item = new HashMap<>();
        for (LogicalAttribute attr : entity.attributes()) {
            Object value = row.get(attr.name());
            if (value == null) continue;
            item.put(attr.name(), toAttribute(value));
        }
        return item;
    }

    private AttributeValue toAttribute(Object value) {
        if (value instanceof Boolean b) return AttributeValue.fromBool(b);
        if (value instanceof Number n) return AttributeValue.fromN(BigDecimal.valueOf(n.doubleValue()).toPlainString());
        if (value instanceof float[] arr) {
            List<AttributeValue> list = new ArrayList<>(arr.length);
            for (float f : arr) list.add(AttributeValue.fromN(Float.toString(f)));
            return AttributeValue.fromL(list);
        }
        if (value instanceof java.time.Instant ins) return AttributeValue.fromS(ins.toString());
        if (value instanceof java.time.LocalDate ld) return AttributeValue.fromS(ld.toString());
        if (value instanceof Map<?, ?> m) {
            Map<String, AttributeValue> mapped = new LinkedHashMap<>();
            m.forEach((k, v) -> mapped.put(String.valueOf(k), v == null ? AttributeValue.fromNul(true) : toAttribute(v)));
            return AttributeValue.fromM(mapped);
        }
        if (value instanceof List<?> l) {
            List<AttributeValue> mapped = new ArrayList<>(l.size());
            for (Object o : l) mapped.add(o == null ? AttributeValue.fromNul(true) : toAttribute(o));
            return AttributeValue.fromL(mapped);
        }
        return AttributeValue.fromS(value.toString());
    }

    private int effectiveBatchSize(InsertContext ctx) {
        return switch (ctx.mode()) {
            case SINGLE -> 1;
            case BATCH -> Math.max(1, Math.min(BATCH_LIMIT, ctx.batchSize()));
            case BULK -> Math.min(BATCH_LIMIT, Math.max(1, ctx.batchSize() > 0 ? ctx.batchSize() : BATCH_LIMIT));
        };
    }

    @SuppressWarnings("unused")
    private static final Set<String> UNUSED_RESERVED = new HashSet<>();

    private static final class EntityOutcome {
        long dbTimeNs;
        long rowsAffected;
        List<RecordedId> recordedIds = new ArrayList<>();
    }
}
