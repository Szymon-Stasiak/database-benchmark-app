package com.dbagnets.backend.insert.service;

import com.dbagnets.backend.docker.DockerService;
import com.dbagnets.backend.entity.BenchmarkDatabase;
import com.dbagnets.backend.entity.DatabaseStatus;
import com.dbagnets.backend.insert.entity.InsertStatus;
import com.dbagnets.backend.insert.model.DatabaseSizeResponse;
import com.dbagnets.backend.insert.repository.InsertResultRepository;
import com.dbagnets.backend.insert.size.DatabaseSizeStrategyFactory;
import com.dbagnets.backend.repository.BenchmarkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class DatabaseSizeService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSizeService.class);
    /** Per-probe budget. With 2+ DBs receiving inserts in parallel, {@code du -sB1} inside a busy
     *  Neo4j/Mongo container can comfortably push past 5s, so we give each probe more headroom —
     *  probes run concurrently so the total response time still bottoms out at ~this many seconds. */
    private static final long PROBE_TIMEOUT_SEC = 12L;
    /** Insert phases past PENDING — used to flip the pre-insert clamp off only when a strategy
     *  has actually started writing, not when it's merely been queued by {@code startRun}. */
    private static final List<InsertStatus> POST_PENDING = List.of(
        InsertStatus.RUNNING, InsertStatus.SUCCESS, InsertStatus.FAILED);

    private final BenchmarkRepository benchmarkRepository;
    private final DockerService dockerService;
    private final DatabaseSizeStrategyFactory factory;
    private final InsertResultRepository insertResultRepository;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    /** Last successful probe per database. Returned on timeout/error so the chart doesn't blank
     *  out a DB whose probe was slower than the others on this poll — without the cache the
     *  frontend's {@code dataBytes ?? 0} fallback would zero-out the inserted-data segment. */
    private final ConcurrentHashMap<String, Long> lastGoodSize = new ConcurrentHashMap<>();

    public DatabaseSizeService(
        BenchmarkRepository benchmarkRepository,
        DockerService dockerService,
        DatabaseSizeStrategyFactory factory,
        InsertResultRepository insertResultRepository
    ) {
        this.benchmarkRepository = benchmarkRepository;
        this.dockerService = dockerService;
        this.factory = factory;
        this.insertResultRepository = insertResultRepository;
    }

    @Transactional(readOnly = true)
    public List<DatabaseSizeResponse> sizesFor(String benchmarkId) {
        var benchmark = benchmarkRepository.findById(benchmarkId)
            .orElseThrow(() -> new NoSuchElementException("Benchmark not found: " + benchmarkId));

        // Capture immutable snapshot — DB lookups happen on worker threads where the JPA session is gone.
        // hasActiveInsert = "a strategy has actually started writing to this DB at least once".
        // We use post-PENDING statuses (not mere existence) so the brief window between
        // startRun() (PENDING rows created) and orchestrate() (baseline re-frozen via
        // captureForFirstInsertOnly) still has the clamp ON — no transient frame where engine
        // drift gets booked as user data.
        List<DbSnapshot> snapshots = benchmark.getDatabases().stream()
            .map(db -> DbSnapshot.from(db,
                insertResultRepository.existsByDatabaseIdAndStatusIn(db.getId(), POST_PENDING)))
            .toList();

        List<Future<DatabaseSizeResponse>> futures = snapshots.stream()
            .map(s -> executor.submit(() -> probe(s)))
            .toList();

        List<DatabaseSizeResponse> out = new java.util.ArrayList<>(futures.size());
        for (int i = 0; i < futures.size(); i++) {
            DbSnapshot s = snapshots.get(i);
            try {
                out.add(futures.get(i).get(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS));
            } catch (TimeoutException te) {
                futures.get(i).cancel(true);
                log.warn("Size probe for {} ({}) exceeded {}s — returning last known size",
                    s.dbName, s.id, PROBE_TIMEOUT_SEC);
                out.add(fallbackFor(s));
            } catch (Exception e) {
                log.warn("Size probe failed for {} ({})", s.dbName, s.id, e);
                out.add(fallbackFor(s));
            }
        }
        return out;
    }

    private DatabaseSizeResponse probe(DbSnapshot db) {
        if (db.containerId == null || db.status != DatabaseStatus.RUNNING) {
            return DatabaseSizeResponse.of(db.id, db.dbName, db.dbVersion, -1, db.baselineBytes);
        }
        long size = factory.create(db.dbName).sizeBytes(dockerService, db.containerId, db.hostPort);
        if (size >= 0) {
            lastGoodSize.put(db.id, size);
        }
        return buildResponse(db, size);
    }

    /**
     * Apply the "pre-insert hold" rule: until the first insert is queued for a DB, the bar must
     * be visually stable — no engine background writes (WAL, autovacuum, checkpointing, journal
     * sync) showing up as either a growing baseline OR phantom dataBytes. We achieve this by
     * reporting {@code sizeBytes = baselineBytes}, which makes the chart compute dataBytes = 0
     * AND keeps the total bar height pinned to the captured baseline.
     *
     * <p>Once {@code hasActiveInsert} flips true, we report the real size; the orchestrator
     * re-froze the baseline right before the inserts began, so the delta is true user payload.
     */
    private DatabaseSizeResponse buildResponse(DbSnapshot db, long sizeBytes) {
        if (!db.hasActiveInsert && sizeBytes >= 0 && db.baselineBytes != null) {
            return DatabaseSizeResponse.of(db.id, db.dbName, db.dbVersion, db.baselineBytes, db.baselineBytes);
        }
        return DatabaseSizeResponse.of(db.id, db.dbName, db.dbVersion, sizeBytes, db.baselineBytes);
    }

    /**
     * Build a response from the last successful probe so a slow/stuck poll doesn't blank out
     * the chart segment for that DB. Only falls back to "n/a" if we've never seen a good
     * probe for this database (e.g. container only just turned RUNNING).
     */
    private DatabaseSizeResponse fallbackFor(DbSnapshot s) {
        Long cached = lastGoodSize.get(s.id);
        if (cached != null) {
            return buildResponse(s, cached);
        }
        return DatabaseSizeResponse.of(s.id, s.dbName, s.dbVersion, -1, s.baselineBytes);
    }

    private record DbSnapshot(String id, String dbName, String dbVersion, String containerId,
                              Integer hostPort, DatabaseStatus status, Long baselineBytes,
                              boolean hasActiveInsert) {
        static DbSnapshot from(BenchmarkDatabase db, boolean hasActiveInsert) {
            return new DbSnapshot(db.getId(), db.getDbName(), db.getDbVersion(), db.getContainerId(),
                db.getHostPort(), db.getStatus(), db.getBaselineSizeBytes(), hasActiveInsert);
        }
    }
}
