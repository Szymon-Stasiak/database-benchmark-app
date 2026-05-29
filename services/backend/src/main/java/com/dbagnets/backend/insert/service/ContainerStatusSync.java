package com.dbagnets.backend.insert.service;

import com.dbagnets.backend.docker.DockerService;
import com.dbagnets.backend.entity.BenchmarkDatabase;
import com.dbagnets.backend.entity.DatabaseStatus;
import com.dbagnets.backend.repository.BenchmarkDatabaseRepository;
import com.dbagnets.backend.sse.SseEmitterService;
import com.dbagnets.backend.sse.SseEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Reconciles {@link BenchmarkDatabase#getStatus()} with the actual Docker container state.
 *
 * <p>Without this, after a backend restart the DB still says {@code RUNNING} for containers
 * that Docker has since stopped/removed — and any insert run blows up with a 409 from the
 * Docker engine. Sync runs:
 * <ol>
 *   <li>once on application start ({@link ApplicationReadyEvent}),</li>
 *   <li>every {@value #SYNC_PERIOD_MS} ms thereafter, so manual {@code docker stop} also
 *       gets reflected in the UI within a minute.</li>
 * </ol>
 */
@Component
public class ContainerStatusSync {

    private static final Logger log = LoggerFactory.getLogger(ContainerStatusSync.class);
    private static final long SYNC_PERIOD_MS = 60_000;
    private static final List<DatabaseStatus> SUPPOSEDLY_LIVE = List.of(
        DatabaseStatus.RUNNING,
        DatabaseStatus.INITIALIZING,
        DatabaseStatus.CONTAINER_STARTING
    );

    private final BenchmarkDatabaseRepository databaseRepository;
    private final DockerService dockerService;
    private final SseEmitterService sseEmitterService;

    public ContainerStatusSync(
        BenchmarkDatabaseRepository databaseRepository,
        DockerService dockerService,
        SseEmitterService sseEmitterService
    ) {
        this.databaseRepository = databaseRepository;
        this.dockerService = dockerService;
        this.sseEmitterService = sseEmitterService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            sync();
        } catch (Exception e) {
            log.warn("Initial container status sync failed", e);
        }
    }

    @Scheduled(fixedDelay = SYNC_PERIOD_MS, initialDelay = SYNC_PERIOD_MS)
    public void scheduledSync() {
        try {
            sync();
        } catch (Exception e) {
            log.warn("Periodic container status sync failed", e);
        }
    }

    @Transactional
    public void sync() {
        List<BenchmarkDatabase> candidates = databaseRepository.findByStatusIn(SUPPOSEDLY_LIVE);
        int touched = 0;
        for (BenchmarkDatabase db : candidates) {
            if (db.getContainerId() == null) continue;
            if (dockerService.isContainerRunning(db.getContainerId())) continue;

            db.setStatus(DatabaseStatus.STOPPED);
            db.setErrorMessage("Container is no longer running (detected by status sync)");
            databaseRepository.save(db);
            sseEmitterService.sendEvent(db.getBenchmark().getId(), SseEvents.EVENT_DATABASE_STATUS, Map.of(
                SseEvents.PAYLOAD_BENCHMARK_ID, db.getBenchmark().getId(),
                SseEvents.PAYLOAD_DATABASE_ID, db.getId(),
                SseEvents.PAYLOAD_STATUS, DatabaseStatus.STOPPED.name(),
                SseEvents.PAYLOAD_ERROR_MESSAGE, db.getErrorMessage()
            ));
            touched++;
        }
        if (touched > 0) {
            log.info("Container status sync: marked {} database(s) as STOPPED (were RUNNING in DB, not running in Docker)", touched);
        }
    }
}
