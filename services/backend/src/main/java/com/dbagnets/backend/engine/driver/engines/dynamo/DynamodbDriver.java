package com.dbagnets.backend.engine.driver.engines.dynamo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.dbagnets.backend.domain.DatabaseEngine;
import com.dbagnets.backend.engine.cascade.CascadeNode;
import com.dbagnets.backend.engine.datagen.GeneratedRow;
import com.dbagnets.backend.engine.driver.api.DeleteContext;
import com.dbagnets.backend.engine.driver.api.EngineDriver;
import com.dbagnets.backend.engine.driver.api.EntityOutcome;
import com.dbagnets.backend.engine.driver.api.InsertContext;
import com.dbagnets.backend.engine.driver.api.ReadContext;
import com.dbagnets.backend.engine.driver.support.BatchSizes;
import com.dbagnets.backend.engine.driver.support.BulkInsertLoop;
import com.dbagnets.backend.engine.driver.support.InsertOuterLoop;
import com.dbagnets.backend.engine.driver.support.PerTargetLoop;
import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalEntity;
import com.dbagnets.backend.engine.schema.LogicalSchema;
import com.dbagnets.backend.engine.timing.TimedOperation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

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
    public TimedOperation insert(InsertContext ctx) throws Exception {
        DynamoDbClient client =
                clientCache.get(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        return InsertOuterLoop.run(
                ctx,
                (node, rows) -> {
                    LogicalEntity entity = ctx.schema().requireEntity(node.entityName());
                    String table = node.entityName().toLowerCase();
                    ensureTable(client, table, entity);
                    return bulkInsert(client, ctx, node, entity, table, rows);
                });
    }

    @Override
    public TimedOperation read(ReadContext ctx) {
        DynamoCtx d =
                resolveCtx(
                        ctx.databaseId(),
                        ctx.hostAddress(),
                        ctx.hostPort(),
                        ctx.entityName(),
                        ctx.schema());
        return PerTargetLoop.run(
                ctx.targets(),
                "DynamoDB read",
                e -> d.table() + "/" + e.physicalId(),
                entry -> {
                    var resp =
                            d.client()
                                    .getItem(
                                            GetItemRequest.builder()
                                                    .tableName(d.table())
                                                    .key(
                                                            Map.of(
                                                                    d.pkName(),
                                                                    AttributeValue.fromS(
                                                                            String.valueOf(
                                                                                    entry
                                                                                            .physicalId()))))
                                                    .build());
                    return resp.hasItem() && !resp.item().isEmpty() ? 1 : 0;
                });
    }

    @Override
    public TimedOperation delete(DeleteContext ctx) {
        DynamoCtx d =
                resolveCtx(
                        ctx.databaseId(),
                        ctx.hostAddress(),
                        ctx.hostPort(),
                        ctx.entityName(),
                        ctx.schema());
        return PerTargetLoop.run(
                ctx.targets(),
                "DynamoDB delete",
                e -> d.table() + "/" + e.physicalId(),
                entry -> {
                    d.client()
                            .deleteItem(
                                    DeleteItemRequest.builder()
                                            .tableName(d.table())
                                            .key(
                                                    Map.of(
                                                            d.pkName(),
                                                            AttributeValue.fromS(
                                                                    String.valueOf(
                                                                            entry.physicalId()))))
                                            .build());
                    return 1;
                });
    }

    private void ensureTable(DynamoDbClient client, String table, LogicalEntity entity) {
        if (!ensuredTables.add(table)) return;
        try {
            client.describeTable(DescribeTableRequest.builder().tableName(table).build());
            return;
        } catch (Exception ignore) {
        }
        String pkName =
                entity.primaryKey()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Entity missing PK: " + entity.name()))
                        .name();
        try {
            client.createTable(
                    CreateTableRequest.builder()
                            .tableName(table)
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName(pkName)
                                            .keyType(KeyType.HASH)
                                            .build())
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName(pkName)
                                            .attributeType(ScalarAttributeType.S)
                                            .build())
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .build());
        } catch (Exception ex) {
            log.warn("DynamoDB createTable failed for {}: {}", table, ex.getMessage());
        }
    }

    private EntityOutcome bulkInsert(
            DynamoDbClient client,
            InsertContext ctx,
            CascadeNode node,
            LogicalEntity entity,
            String table,
            List<GeneratedRow> rows)
            throws Exception {
        BulkInsertLoop.Config config =
                new BulkInsertLoop.Config(
                        BatchSizes.effectiveCapped(ctx, BATCH_LIMIT, BATCH_LIMIT),
                        engine(),
                        false,
                        "DynamoDB batchWrite failed on {} batch {}: {}",
                        null,
                        table);
        return BulkInsertLoop.run(
                ctx,
                node,
                rows,
                config,
                (slice, batchIndex, totalBatches) -> {
                    List<WriteRequest> writes = new ArrayList<>(slice.size());
                    for (GeneratedRow row : slice) {
                        writes.add(
                                WriteRequest.builder()
                                        .putRequest(
                                                PutRequest.builder()
                                                        .item(toItem(entity, row))
                                                        .build())
                                        .build());
                    }
                    client.batchWriteItem(
                            BatchWriteItemRequest.builder()
                                    .requestItems(Map.of(table, writes))
                                    .build());
                    return slice.size();
                });
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
        if (value instanceof Number n)
            return AttributeValue.fromN(BigDecimal.valueOf(n.doubleValue()).toPlainString());
        if (value instanceof float[] arr) {
            List<AttributeValue> list = new ArrayList<>(arr.length);
            for (float f : arr) list.add(AttributeValue.fromN(Float.toString(f)));
            return AttributeValue.fromL(list);
        }
        if (value instanceof java.time.Instant ins) return AttributeValue.fromS(ins.toString());
        if (value instanceof java.time.LocalDate ld) return AttributeValue.fromS(ld.toString());
        if (value instanceof Map<?, ?> m) {
            Map<String, AttributeValue> mapped = new LinkedHashMap<>();
            m.forEach(
                    (k, v) ->
                            mapped.put(
                                    String.valueOf(k),
                                    v == null ? AttributeValue.fromNul(true) : toAttribute(v)));
            return AttributeValue.fromM(mapped);
        }
        if (value instanceof List<?> l) {
            List<AttributeValue> mapped = new ArrayList<>(l.size());
            for (Object o : l)
                mapped.add(o == null ? AttributeValue.fromNul(true) : toAttribute(o));
            return AttributeValue.fromL(mapped);
        }
        return AttributeValue.fromS(value.toString());
    }

    @SuppressWarnings("unused")
    private static final Set<String> UNUSED_RESERVED = new HashSet<>();

    private DynamoCtx resolveCtx(
            String databaseId,
            String hostAddress,
            int hostPort,
            String entityName,
            LogicalSchema schema) {
        DynamoDbClient client = clientCache.get(databaseId, hostAddress, hostPort);
        String pkName =
                schema.requireEntity(entityName)
                        .primaryKey()
                        .orElseThrow(
                                () -> new IllegalStateException("Entity missing PK: " + entityName))
                        .name();
        return new DynamoCtx(client, pkName, entityName.toLowerCase());
    }

    private record DynamoCtx(DynamoDbClient client, String pkName, String table) {}
}
