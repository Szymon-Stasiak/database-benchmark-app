package com.dbagnets.backend.insert.strategy.redis;

import com.dbagnets.backend.docker.DockerService;
import com.dbagnets.backend.insert.datagen.GeneratedRecord;
import com.dbagnets.backend.insert.entity.InsertMode;
import com.dbagnets.backend.insert.strategy.Batch;
import com.dbagnets.backend.insert.strategy.BatchProgressCallback;
import com.dbagnets.backend.insert.strategy.DatabaseInsertStrategy;
import com.dbagnets.backend.insert.strategy.InsertContext;
import com.dbagnets.backend.insert.strategy.InsertOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Redis inserts via Jedis. Each record is stored as one JSON-encoded string value
 * (key = "{entity}:{uuid}"). For BATCH/BULK we use {@link Pipeline} which sends all SETs in a
 * single round-trip — the standard high-throughput Redis pattern.
 */
@RequiredArgsConstructor
@Slf4j
public class RedisJedisStrategy implements DatabaseInsertStrategy {

    private static final Batch POISON = new Batch(-1, -1, List.of());

    private final ObjectMapper mapper;

    @Override
    public InsertOutcome insert(DockerService docker, InsertContext ctx, BatchProgressCallback progress) {
        if (ctx.hostPort() == null) {
            return InsertOutcome.failure("Database has no host port mapping yet", 0);
        }
        int workers = Math.max(1, ctx.effectiveWorkerCount());
        List<Batch> batches = partition(ctx);
        if (batches.isEmpty()) return InsertOutcome.success(0, 0);

        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(workers);
        cfg.setMaxIdle(workers);
        cfg.setMinIdle(workers);

        try (JedisPool pool = new JedisPool(cfg, ctx.host(), ctx.hostPort())) {
            return runBatches(ctx, pool, batches, workers, progress);
        } catch (Exception e) {
            log.warn("Redis insert failed for {}", ctx.dbName(), e);
            return InsertOutcome.failure("Redis insert failed: " + e.getMessage(), 0);
        }
    }

    private InsertOutcome runBatches(
        InsertContext ctx,
        JedisPool pool,
        List<Batch> batches,
        int workers,
        BatchProgressCallback progress
    ) {
        BlockingQueue<Batch> queue = new ArrayBlockingQueue<>(batches.size() + workers);
        for (Batch b : batches) queue.add(b);
        for (int i = 0; i < workers; i++) queue.add(POISON);

        AtomicLong firstStartNs = new AtomicLong(Long.MAX_VALUE);
        AtomicLong lastEndNs = new AtomicLong(0);
        AtomicInteger doneRecords = new AtomicInteger();
        AtomicInteger doneBatches = new AtomicInteger();
        AtomicReference<String> failure = new AtomicReference<>();

        try (ExecutorService exec = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
            List<Future<?>> futures = new ArrayList<>(workers);
            for (int i = 0; i < workers; i++) {
                futures.add(exec.submit(() -> {
                    try (Jedis jedis = pool.getResource()) {
                        while (true) {
                            Batch batch = queue.poll();
                            if (batch == null || batch == POISON) return;
                            long t0 = System.nanoTime();
                            if (ctx.mode() == InsertMode.SINGLE) {
                                for (GeneratedRecord r : batch.records()) {
                                    jedis.set(keyFor(ctx, r), toJson(r));
                                }
                            } else {
                                Pipeline pipeline = jedis.pipelined();
                                for (GeneratedRecord r : batch.records()) {
                                    pipeline.set(keyFor(ctx, r), toJson(r));
                                }
                                pipeline.sync();
                            }
                            long t1 = System.nanoTime();
                            firstStartNs.updateAndGet(prev -> Math.min(prev, t0));
                            lastEndNs.updateAndGet(prev -> Math.max(prev, t1));
                            int done = doneRecords.addAndGet(batch.size());
                            int idx = doneBatches.getAndIncrement();
                            progress.onBatch(idx, batches.size(), done);
                        }
                    } catch (Exception e) {
                        log.warn("Redis worker failed", e);
                        failure.compareAndSet(null, e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }));
            }
            for (Future<?> f : futures) {
                try { f.get(); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return InsertOutcome.failure("Interrupted", 0); }
                catch (ExecutionException ee) { failure.compareAndSet(null, "Worker failed: " + ee.getCause().getMessage()); }
            }
        }

        long ms = durationMs(firstStartNs, lastEndNs);
        if (failure.get() != null) return InsertOutcome.failure(failure.get(), ms);
        return InsertOutcome.success(doneRecords.get(), ms);
    }

    private static String keyFor(InsertContext ctx, GeneratedRecord r) {
        // Prefer a PK-ish attribute if present; otherwise a random UUID guarantees uniqueness.
        for (var e : r.values().entrySet()) {
            String n = e.getKey().toLowerCase();
            if ((n.equals("id") || n.endsWith("_id") || n.endsWith("id")) && e.getValue() != null) {
                return ctx.entityName() + ":" + e.getValue();
            }
        }
        return ctx.entityName() + ":" + UUID.randomUUID();
    }

    private String toJson(GeneratedRecord r) {
        try {
            Map<String, Object> normalized = new LinkedHashMap<>();
            r.values().forEach((k, v) -> normalized.put(k, v == null ? null : v.toString()));
            return mapper.writeValueAsString(normalized);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static long durationMs(AtomicLong s, AtomicLong e) {
        long sv = s.get(); long ev = e.get();
        if (sv == Long.MAX_VALUE || ev <= sv) return 0;
        return (ev - sv) / 1_000_000L;
    }

    private static List<Batch> partition(InsertContext ctx) {
        List<GeneratedRecord> records = ctx.records();
        if (records.isEmpty()) return List.of();
        int size = switch (ctx.mode()) {
            case SINGLE -> 1;
            case BATCH -> Math.max(1, ctx.effectiveBatchSize());
            case BULK -> records.size();
        };
        List<Batch> out = new ArrayList<>();
        int total = (records.size() + size - 1) / size;
        for (int i = 0, idx = 0; i < records.size(); i += size, idx++) {
            out.add(new Batch(idx, total, records.subList(i, Math.min(i + size, records.size()))));
        }
        return out;
    }
}
