package com.dbagnets.backend.benchmark.driver.redis;

import com.dbagnets.backend.benchmark.cascade.CascadeNode;
import com.dbagnets.backend.benchmark.datagen.GeneratedRow;
import com.dbagnets.backend.benchmark.driver.DeleteContext;
import com.dbagnets.backend.benchmark.driver.EngineDriver;
import com.dbagnets.backend.benchmark.driver.InsertContext;
import com.dbagnets.backend.benchmark.driver.ReadContext;
import com.dbagnets.backend.benchmark.registry.EntityIdRegistry.RegistryEntry;
import com.dbagnets.backend.benchmark.timing.RecordedId;
import com.dbagnets.backend.benchmark.timing.TimedOperation;
import com.dbagnets.backend.entity.DatabaseEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisDriver implements EngineDriver {

    private static final int PING_SAMPLES = 16;

    private final RedisPoolCache poolCache;
    private final ObjectMapper objectMapper;

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.REDIS;
    }

    @Override
    public TimedOperation insert(InsertContext ctx) throws Exception {
        JedisPool pool = poolCache.get(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());

        long pingBaselineNs;
        try (Jedis jedis = pool.getResource()) {
            pingBaselineNs = measurePingBaselinePerCommand(jedis);
        }

        long totalDbTimeNs = 0L;
        long totalRowsAffected = 0L;
        List<RecordedId> recordedIds = new ArrayList<>();

        long wireStart = System.nanoTime();
        try (Jedis jedis = pool.getResource()) {
            for (CascadeNode node : ctx.plan().nodesInInsertOrder()) {
                List<GeneratedRow> rows = ctx.rowsByEntity().get(node.entityName());
                if (rows == null || rows.isEmpty()) continue;
                long opNs = writeEntity(jedis, ctx, node, rows, recordedIds);
                totalDbTimeNs += Math.max(0L, opNs - pingBaselineNs * rows.size());
                totalRowsAffected += rows.size();
                ctx.progress().onEntityFinished(node.entityName());
            }
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
        JedisPool pool = poolCache.get(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        long pingBaselineNs;
        try (Jedis jedis = pool.getResource()) {
            pingBaselineNs = measurePingBaselinePerCommand(jedis);
        }

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsRead = 0L;

        long wireStart = System.nanoTime();
        try (Jedis jedis = pool.getResource()) {
            String prefix = ctx.entityName().toLowerCase() + ":";
            for (int i = 0; i < ctx.targets().size(); i++) {
                RegistryEntry entry = ctx.targets().get(i);
                String key = entry.physicalId() != null && entry.physicalId().startsWith(prefix)
                        ? entry.physicalId()
                        : prefix + entry.logicalId();
                long start = System.nanoTime();
                String value = jedis.get(key);
                long sampleNs = Math.max(0L, System.nanoTime() - start - pingBaselineNs);
                samples[i] = sampleNs;
                totalDbTimeNs += sampleNs;
                if (value != null) rowsRead++;
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
        JedisPool pool = poolCache.get(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        long pingBaselineNs;
        try (Jedis jedis = pool.getResource()) {
            pingBaselineNs = measurePingBaselinePerCommand(jedis);
        }

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsAffected = 0L;

        long wireStart = System.nanoTime();
        try (Jedis jedis = pool.getResource()) {
            String prefix = ctx.entityName().toLowerCase() + ":";
            for (int i = 0; i < ctx.targets().size(); i++) {
                RegistryEntry entry = ctx.targets().get(i);
                String key = entry.physicalId() != null && entry.physicalId().startsWith(prefix)
                        ? entry.physicalId()
                        : prefix + entry.logicalId();
                long start = System.nanoTime();
                long deleted = jedis.del(key);
                long sampleNs = Math.max(0L, System.nanoTime() - start - pingBaselineNs);
                samples[i] = sampleNs;
                totalDbTimeNs += sampleNs;
                rowsAffected += deleted;
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

    private long writeEntity(Jedis jedis,
                              InsertContext ctx,
                              CascadeNode node,
                              List<GeneratedRow> rows,
                              List<RecordedId> recordedIds) throws Exception {
        int batchSize = effectiveBatchSize(ctx);
        int totalBatches = Math.max(1, (int) Math.ceil((double) rows.size() / batchSize));
        long opNs = 0L;
        int batchIndex = 0;
        String entityLowerName = node.entityName().toLowerCase();

        for (int from = 0; from < rows.size(); from += batchSize) {
            int to = Math.min(from + batchSize, rows.size());
            List<GeneratedRow> slice = rows.subList(from, to);
            long start = System.nanoTime();
            try (Pipeline pipeline = jedis.pipelined()) {
                for (GeneratedRow row : slice) {
                    String key = entityLowerName + ":" + row.logicalId();
                    pipeline.set(key, objectMapper.writeValueAsString(row.values()));
                    recordedIds.add(new RecordedId(node.entityName(), row.logicalId(), key));
                }
                pipeline.sync();
            }
            opNs += System.nanoTime() - start;
            batchIndex++;
            ctx.progress().onBatch(node.entityName(), batchIndex, totalBatches, to, rows.size());
        }
        return opNs;
    }

    private long measurePingBaselinePerCommand(Jedis jedis) {
        long total = 0L;
        for (int i = 0; i < PING_SAMPLES; i++) {
            long start = System.nanoTime();
            jedis.ping();
            total += System.nanoTime() - start;
        }
        return total / PING_SAMPLES;
    }

    private int effectiveBatchSize(InsertContext ctx) {
        return switch (ctx.mode()) {
            case SINGLE -> 1;
            case BATCH -> Math.max(1, ctx.batchSize());
            case BULK -> Math.max(1, ctx.batchSize() > 0 ? ctx.batchSize() : 10_000);
        };
    }
}
