package com.dbagnets.backend.insert.strategy.neo4j;

import com.dbagnets.backend.docker.DockerService;
import com.dbagnets.backend.insert.datagen.GeneratedRecord;
import com.dbagnets.backend.insert.entity.InsertMode;
import com.dbagnets.backend.insert.strategy.Batch;
import com.dbagnets.backend.insert.strategy.BatchProgressCallback;
import com.dbagnets.backend.insert.strategy.DatabaseInsertStrategy;
import com.dbagnets.backend.insert.strategy.InsertContext;
import com.dbagnets.backend.insert.strategy.InsertOutcome;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Bolt-based insert strategy that handles both Neo4j and Memgraph (their Bolt protocols are
 * compatible). One shared {@link Driver} per phase, one Session per worker virtual thread;
 * timing wraps only the {@code session.run(...)} call so the user sees the actual server-side
 * graph mutation cost.
 *
 * <p>All records for a batch are sent in a single {@code UNWIND $rows CREATE (n:Label) SET n = row}
 * — the most efficient bulk path in both engines.
 */
@Slf4j
public class Neo4jNativeStrategy implements DatabaseInsertStrategy {

    private static final Batch POISON = new Batch(-1, -1, List.of());

    private final String boltScheme;
    private final String username;
    private final String password;

    public Neo4jNativeStrategy() {
        this("bolt", "neo4j", "benchmark");
    }

    public Neo4jNativeStrategy(String boltScheme, String username, String password) {
        this.boltScheme = boltScheme;
        this.username = username;
        this.password = password;
    }

    @Override
    public InsertOutcome insert(DockerService docker, InsertContext ctx, BatchProgressCallback progress) {
        if (ctx.hostPort() == null) {
            return InsertOutcome.failure("Database has no host port mapping yet", 0);
        }
        int workers = Math.max(1, ctx.effectiveWorkerCount());
        List<Batch> batches = partition(ctx);
        if (batches.isEmpty()) return InsertOutcome.success(0, 0);

        String uri = boltScheme + "://" + ctx.host() + ":" + ctx.hostPort();
        Config cfg = Config.builder().withMaxConnectionPoolSize(workers).build();

        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password), cfg)) {
            driver.verifyConnectivity();
            return runBatches(ctx, driver, batches, workers, progress);
        } catch (Exception e) {
            log.warn("Bolt insert failed for {}", ctx.dbName(), e);
            return InsertOutcome.failure("Bolt insert failed: " + e.getMessage(), 0);
        }
    }

    private InsertOutcome runBatches(
        InsertContext ctx,
        Driver driver,
        List<Batch> batches,
        int workers,
        BatchProgressCallback progress
    ) {
        String label = labelName(ctx.entityName());
        String cypher = "UNWIND $rows AS row CREATE (n:" + label + ") SET n = row";

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
                    try (Session session = driver.session()) {
                        while (true) {
                            Batch batch = queue.poll();
                            if (batch == null || batch == POISON) return;
                            List<Map<String, Object>> rows = new ArrayList<>(batch.size());
                            for (GeneratedRecord r : batch.records()) rows.add(toRow(r));
                            long t0 = System.nanoTime();
                            if (ctx.mode() == InsertMode.SINGLE) {
                                for (Map<String, Object> row : rows) {
                                    session.run(cypher, Map.of("rows", List.of(row))).consume();
                                }
                            } else {
                                session.run(cypher, Map.of("rows", rows)).consume();
                            }
                            long t1 = System.nanoTime();
                            firstStartNs.updateAndGet(prev -> Math.min(prev, t0));
                            lastEndNs.updateAndGet(prev -> Math.max(prev, t1));
                            int done = doneRecords.addAndGet(batch.size());
                            int idx = doneBatches.getAndIncrement();
                            progress.onBatch(idx, batches.size(), done);
                        }
                    } catch (Exception e) {
                        log.warn("Bolt worker failed", e);
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

    private static Map<String, Object> toRow(GeneratedRecord r) {
        Map<String, Object> values = new LinkedHashMap<>(r.values().size());
        r.values().forEach((k, v) -> values.put(k, normalize(v)));
        return values;
    }

    private static Object normalize(Object v) {
        if (v == null) return null;
        if (v instanceof java.time.LocalDate d) return d.toString();
        if (v instanceof java.time.LocalTime t) return t.toString();
        if (v instanceof java.time.Instant i) return i.toString();
        if (v instanceof double[] arr) {
            List<Double> list = new ArrayList<>(arr.length);
            for (double d : arr) list.add(d);
            return list;
        }
        // Neo4j refuses Map / nested object property values — flatten to its toString() form so
        // the JSON-ish payload survives at least as a primitive string.
        if (v instanceof java.util.Map<?, ?> || v instanceof Iterable<?> && !(v instanceof List<?>)) {
            return v.toString();
        }
        if (v instanceof List<?> list) {
            // Neo4j accepts arrays of primitives only; if any element is non-primitive, stringify.
            for (Object e : list) {
                if (e != null && !(e instanceof Number || e instanceof Boolean || e instanceof String)) {
                    return v.toString();
                }
            }
            return list;
        }
        return v;
    }

    private static String labelName(String entityName) {
        if (entityName == null || entityName.isEmpty()) return "Entity";
        char first = Character.toUpperCase(entityName.charAt(0));
        return first + entityName.substring(1).replaceAll("[^A-Za-z0-9_]", "_");
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
