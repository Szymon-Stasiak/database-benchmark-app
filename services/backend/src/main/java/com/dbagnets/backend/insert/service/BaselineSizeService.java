package com.dbagnets.backend.insert.service;

import com.dbagnets.backend.docker.DockerService;
import com.dbagnets.backend.entity.BenchmarkDatabase;
import com.dbagnets.backend.entity.DatabaseStatus;
import com.dbagnets.backend.insert.entity.InsertStatus;
import com.dbagnets.backend.insert.repository.InsertResultRepository;
import com.dbagnets.backend.insert.size.DatabaseSizeStrategyFactory;
import com.dbagnets.backend.repository.BenchmarkDatabaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Captures the "empty DB" size for each {@link BenchmarkDatabase} once its container reaches
 * {@link DatabaseStatus#RUNNING}. Snapshot lives on the entity and only changes when the DB is
 * redeployed (the redeploy flow nulls it out and this service re-captures it on the next sync).
 *
 * <p>Runs as a periodic scheduled job so we don't need to bolt this into every status-change path
 * (deploy, redeploy, container-status-sync). Polling once a minute is fine — the baseline is only
 * interesting until the first insert lands.
 */
@Component
public class BaselineSizeService {

    private static final Logger log = LoggerFactory.getLogger(BaselineSizeService.class);
    private static final long PERIOD_MS = 30_000L;

    private static final List<InsertStatus> POST_PENDING = List.of(
        InsertStatus.RUNNING, InsertStatus.SUCCESS, InsertStatus.FAILED);

    private final BenchmarkDatabaseRepository databaseRepository;
    private final DockerService dockerService;
    private final DatabaseSizeStrategyFactory sizeStrategyFactory;
    private final InsertResultRepository insertResultRepository;

    public BaselineSizeService(
        BenchmarkDatabaseRepository databaseRepository,
        DockerService dockerService,
        DatabaseSizeStrategyFactory sizeStrategyFactory,
        InsertResultRepository insertResultRepository
    ) {
        this.databaseRepository = databaseRepository;
        this.dockerService = dockerService;
        this.sizeStrategyFactory = sizeStrategyFactory;
        this.insertResultRepository = insertResultRepository;
    }

    @Scheduled(fixedDelay = PERIOD_MS, initialDelay = PERIOD_MS)
    public void scheduledCapture() {
        try {
            captureMissingBaselines();
        } catch (Exception e) {
            log.warn("Baseline capture failed", e);
        }
    }

    @Transactional
    public void captureMissingBaselines() {
        List<BenchmarkDatabase> running = databaseRepository.findByStatusIn(List.of(DatabaseStatus.RUNNING));
        int captured = 0;
        for (BenchmarkDatabase db : running) {
            // Baseline is frozen once captured — never overwrite. The orchestrator re-freezes it
            // explicitly via captureFor() right before the first insert run starts, which is the
            // only moment we want it to move. Engine background writes between RUNNING and the
            // first insert are hidden from the chart by DatabaseSizeService.buildResponse().
            if (db.getBaselineSizeBytes() != null) continue;
            if (db.getContainerId() == null) continue;
            long size = sizeStrategyFactory.create(db.getDbName())
                .sizeBytes(dockerService, db.getContainerId(), db.getHostPort());
            if (size < 0) continue;
            db.setBaselineSizeBytes(size);
            db.setBaselineRecordedAt(Instant.now());
            databaseRepository.save(db);
            captured++;
        }
        if (captured > 0) {
            log.info("Baseline size captured for {} database(s)", captured);
        }
    }

    /** Called by the redeploy flow (or the user manually) to force a refresh on the next sync. */
    @Transactional
    public void clearBaseline(String databaseId) {
        databaseRepository.findById(databaseId).ifPresent(db -> {
            db.setBaselineSizeBytes(null);
            db.setBaselineRecordedAt(null);
            databaseRepository.save(db);
        });
    }

    /**
     * Re-freezes the baseline ONLY if no previous insert run has ever written to this DB
     * (i.e. every {@link com.dbagnets.backend.insert.entity.InsertResult} for this database
     * is still PENDING from the just-created current run). Call this from the orchestrator
     * right before starting the very first insert, so the baseline absorbs any post-init
     * engine drift (WAL rotation, checkpointing, etc.) and the chart's pink "data" segment
     * starts at exactly 0.
     *
     * <p>Calling this on the 2nd, 3rd, … insert run is a no-op — leaving baseline locked to
     * its first-insert value so the cyan "engine" segment doesn't keep absorbing the data
     * from previous runs (which would make subsequent runs look like they added almost nothing).
     */
    @Transactional
    public void captureForFirstInsertOnly(String databaseId) {
        if (insertResultRepository.existsByDatabaseIdAndStatusIn(databaseId, POST_PENDING)) {
            log.debug("Skipping baseline freeze for {} — previous insert results exist", databaseId);
            return;
        }
        captureFor(databaseId);
    }

    /**
     * Synchronously capture (or re-capture) the baseline for a single database. Called from the
     * orchestration code right after a DB reaches {@link DatabaseStatus#RUNNING}, so the baseline
     * reflects "engine + schema" and any subsequent insert is correctly attributed to user data
     * on the size chart. Without this, the scheduled job can lag up to 30s — long enough that a
     * user could finish inserting 10k records before the baseline is even taken, which made the
     * chart report "data ≈ 0".
     */
    @Transactional
    public void captureFor(String databaseId) {
        BenchmarkDatabase db = databaseRepository.findById(databaseId).orElse(null);
        if (db == null) return;
        if (db.getContainerId() == null) return;
        if (db.getStatus() != DatabaseStatus.RUNNING) return;
        long size = sizeStrategyFactory.create(db.getDbName())
            .sizeBytes(dockerService, db.getContainerId(), db.getHostPort());
        if (size < 0) {
            log.warn("Baseline probe returned -1 for {} ({}); scheduler will retry", db.getDbName(), databaseId);
            return;
        }
        db.setBaselineSizeBytes(size);
        db.setBaselineRecordedAt(Instant.now());
        databaseRepository.save(db);
        log.info("Captured baseline for {} ({}) = {} bytes", db.getDbName(), databaseId, size);
    }
}
