package com.dbagnets.backend.insert.strategy.mongo;

import com.dbagnets.backend.docker.DockerService;
import com.dbagnets.backend.insert.datagen.GeneratedRecord;
import com.dbagnets.backend.insert.entity.InsertMode;
import com.dbagnets.backend.insert.strategy.Batch;
import com.dbagnets.backend.insert.strategy.BatchProgressCallback;
import com.dbagnets.backend.insert.strategy.DatabaseInsertStrategy;
import com.dbagnets.backend.insert.strategy.InsertContext;
import com.dbagnets.backend.insert.strategy.InsertOutcome;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.InsertManyOptions;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * MongoDB inserts via the native sync driver, with one shared {@link MongoClient} sized to the
 * worker count. Times only the {@code insertMany}/{@code insertOne} call so the user gets the
 * actual server-side latency without driver-setup overhead.
 */
@Slf4j
public class MongoNativeStrategy implements DatabaseInsertStrategy {

    private static final Batch POISON = new Batch(-1, -1, List.of());

    @Override
    public InsertOutcome insert(DockerService docker, InsertContext ctx, BatchProgressCallback progress) {
        if (ctx.hostPort() == null) {
            return InsertOutcome.failure("Database has no host port mapping yet", 0);
        }
        int workers = Math.max(1, ctx.effectiveWorkerCount());
        List<Batch> batches = partition(ctx);
        if (batches.isEmpty()) return InsertOutcome.success(0, 0);

        ConnectionString uri = new ConnectionString(
            "mongodb://" + ctx.host() + ":" + ctx.hostPort() + "/?maxPoolSize=" + workers);
        MongoClientSettings settings = MongoClientSettings.builder()
            .applyConnectionString(uri)
            .applyToConnectionPoolSettings(b -> b.maxSize(workers).minSize(workers))
            .build();

        try (MongoClient client = MongoClients.create(settings)) {
            MongoCollection<Document> collection = client.getDatabase("benchmark")
                .getCollection(ctx.entityName());
            return runBatches(ctx, collection, batches, workers, progress);
        } catch (Exception e) {
            log.warn("Mongo insert failed for {}", ctx.dbName(), e);
            return InsertOutcome.failure("Mongo insert failed: " + e.getMessage(), 0);
        }
    }

    private InsertOutcome runBatches(
        InsertContext ctx,
        MongoCollection<Document> collection,
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
        InsertManyOptions options = new InsertManyOptions().ordered(false);

        try (ExecutorService exec = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
            List<Future<?>> futures = new ArrayList<>(workers);
            for (int i = 0; i < workers; i++) {
                futures.add(exec.submit(() -> {
                    try {
                        while (true) {
                            Batch batch = queue.poll();
                            if (batch == null || batch == POISON) return;
                            List<Document> docs = new ArrayList<>(batch.size());
                            for (GeneratedRecord r : batch.records()) docs.add(toDocument(r));
                            long t0 = System.nanoTime();
                            if (ctx.mode() == InsertMode.SINGLE) {
                                for (Document d : docs) collection.insertOne(d);
                            } else {
                                collection.insertMany(docs, options);
                            }
                            long t1 = System.nanoTime();
                            firstStartNs.updateAndGet(prev -> Math.min(prev, t0));
                            lastEndNs.updateAndGet(prev -> Math.max(prev, t1));
                            int done = doneRecords.addAndGet(batch.size());
                            int idx = doneBatches.getAndIncrement();
                            progress.onBatch(idx, batches.size(), done);
                        }
                    } catch (Exception e) {
                        log.warn("Mongo worker failed", e);
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

    private static long durationMs(AtomicLong s, AtomicLong e) {
        long sv = s.get(); long ev = e.get();
        if (sv == Long.MAX_VALUE || ev <= sv) return 0;
        return (ev - sv) / 1_000_000L;
    }

    private static Document toDocument(GeneratedRecord r) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        r.values().forEach((k, v) -> values.put(k, normalize(v)));
        return new Document(values);
    }

    private static Object normalize(Object v) {
        if (v == null) return null;
        if (v instanceof java.time.LocalDate d) return d.toString();
        if (v instanceof java.time.LocalTime t) return t.toString();
        if (v instanceof java.time.Instant i) return java.util.Date.from(i);
        if (v instanceof double[] arr) {
            List<Double> list = new ArrayList<>(arr.length);
            for (double d : arr) list.add(d);
            return list;
        }
        return v;
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
