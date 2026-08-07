package com.dbagnets.backend.engine.driver.engines.redis;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

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
import com.dbagnets.backend.engine.driver.support.InsertAccumulator;
import com.dbagnets.backend.engine.driver.support.SampledAccumulator;
import com.dbagnets.backend.engine.registry.EntityIdRegistry.RegistryEntry;
import com.dbagnets.backend.engine.timing.RecordedId;
import com.dbagnets.backend.engine.timing.TimedOperation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisDriver implements EngineDriver {

    private final RedisPoolCache poolCache;
    private final ObjectMapper objectMapper;

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.REDIS;
    }

    @Override
    public TimedOperation insert(InsertContext ctx) throws Exception {
        JedisPool pool = poolCache.get(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        InsertAccumulator acc = new InsertAccumulator();
        long wireStart = System.nanoTime();
        try (Jedis jedis = pool.getResource()) {
            for (CascadeNode node : ctx.plan().nodesInInsertOrder()) {
                List<GeneratedRow> rows = ctx.rowsByEntity().get(node.entityName());
                if (rows == null || rows.isEmpty()) continue;
                acc.accept(writeEntity(jedis, ctx, node, rows));
                ctx.progress().onEntityFinished(node.entityName());
            }
        }
        return acc.finish(System.nanoTime() - wireStart);
    }

    @Override
    public TimedOperation read(ReadContext ctx) {
        JedisPool pool = poolCache.get(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        SampledAccumulator acc = new SampledAccumulator(ctx.targets().size());
        long wireStart = System.nanoTime();
        try (Jedis jedis = pool.getResource()) {
            String prefix = ctx.entityName().toLowerCase() + ":";
            for (int i = 0; i < ctx.targets().size(); i++) {
                RegistryEntry entry = ctx.targets().get(i);
                String key =
                        entry.physicalId() != null && entry.physicalId().startsWith(prefix)
                                ? entry.physicalId()
                                : prefix + entry.logicalId();
                long start = System.nanoTime();
                String value = jedis.get(key);
                acc.sample(i, System.nanoTime() - start, value != null ? 1 : 0);
            }
        }
        return acc.finish(System.nanoTime() - wireStart);
    }

    @Override
    public TimedOperation delete(DeleteContext ctx) {
        JedisPool pool = poolCache.get(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        SampledAccumulator acc = new SampledAccumulator(ctx.targets().size());
        long wireStart = System.nanoTime();
        try (Jedis jedis = pool.getResource()) {
            String prefix = ctx.entityName().toLowerCase() + ":";
            for (int i = 0; i < ctx.targets().size(); i++) {
                RegistryEntry entry = ctx.targets().get(i);
                String key =
                        entry.physicalId() != null && entry.physicalId().startsWith(prefix)
                                ? entry.physicalId()
                                : prefix + entry.logicalId();
                long start = System.nanoTime();
                long deleted = jedis.del(key);
                acc.sample(i, System.nanoTime() - start, deleted);
            }
        }
        return acc.finish(System.nanoTime() - wireStart);
    }

    private EntityOutcome writeEntity(
            Jedis jedis, InsertContext ctx, CascadeNode node, List<GeneratedRow> rows)
            throws Exception {
        String entityLowerName = node.entityName().toLowerCase();
        BulkInsertLoop.Config config =
                new BulkInsertLoop.Config(
                        BatchSizes.effective(ctx, 10_000),
                        engine(),
                        false,
                        "Redis batch write failed on {} batch {}: {}",
                        null,
                        node.entityName());
        List<String> lastBatchKeys = new ArrayList<>();
        return BulkInsertLoop.run(
                ctx,
                node,
                rows,
                config,
                (slice, batchIndex, totalBatches) -> {
                    lastBatchKeys.clear();
                    List<String> payloads = new ArrayList<>(slice.size());
                    for (GeneratedRow row : slice) {
                        String key = entityLowerName + ":" + row.logicalId();
                        lastBatchKeys.add(key);
                        payloads.add(objectMapper.writeValueAsString(row.values()));
                    }
                    try (Pipeline pipeline = jedis.pipelined()) {
                        for (int i = 0; i < lastBatchKeys.size(); i++) {
                            pipeline.set(lastBatchKeys.get(i), payloads.get(i));
                        }
                        pipeline.sync();
                    }
                    return slice.size();
                },
                (row, slice) -> {
                    String key = entityLowerName + ":" + row.logicalId();
                    return new RecordedId(node.entityName(), row.logicalId(), key);
                });
    }
}
