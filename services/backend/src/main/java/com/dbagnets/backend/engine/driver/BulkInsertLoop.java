package com.dbagnets.backend.engine.driver;

import com.dbagnets.backend.domain.DatabaseEngine;
import com.dbagnets.backend.engine.cascade.CascadeNode;
import com.dbagnets.backend.engine.datagen.GeneratedRow;
import com.dbagnets.backend.engine.timing.RecordedId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class BulkInsertLoop {

    private static final Logger log = LoggerFactory.getLogger(BulkInsertLoop.class);

    private BulkInsertLoop() {}

    @FunctionalInterface
    public interface SliceHandler {
        long send(List<GeneratedRow> slice, int batchIndex, int totalBatches) throws Exception;
    }

    public static class Config {
        public final int batchSize;
        public final DatabaseEngine engine;
        public final boolean detectConflicts;
        public final String failureLogFormat;
        public final String conflictLogFormat;
        public final String logSubject;

        public Config(int batchSize, DatabaseEngine engine, boolean detectConflicts,
                       String failureLogFormat, String conflictLogFormat, String logSubject) {
            this.batchSize = batchSize;
            this.engine = engine;
            this.detectConflicts = detectConflicts;
            this.failureLogFormat = failureLogFormat;
            this.conflictLogFormat = conflictLogFormat;
            this.logSubject = logSubject;
        }
    }

    public static EntityOutcome run(InsertContext ctx,
                                     CascadeNode node,
                                     List<GeneratedRow> rows,
                                     Config config,
                                     SliceHandler handler) throws Exception {
        return run(ctx, node, rows, config, handler,
                (row, slice) -> new RecordedId(node.entityName(), row.logicalId(), row.logicalId()));
    }

    @FunctionalInterface
    public interface RecordedIdFactory {
        RecordedId build(GeneratedRow row, List<GeneratedRow> slice);
    }

    public static EntityOutcome run(InsertContext ctx,
                                     CascadeNode node,
                                     List<GeneratedRow> rows,
                                     Config config,
                                     SliceHandler handler,
                                     RecordedIdFactory idFactory) throws Exception {
        EntityOutcome outcome = new EntityOutcome();
        int totalBatches = Math.max(1, (int) Math.ceil((double) rows.size() / config.batchSize));
        int batchIndex = 0;

        for (int from = 0; from < rows.size(); from += config.batchSize) {
            int to = Math.min(from + config.batchSize, rows.size());
            List<GeneratedRow> slice = rows.subList(from, to);
            try {
                long start = System.nanoTime();
                long rowsAffected = handler.send(slice, batchIndex, totalBatches);
                outcome.dbTimeNs += System.nanoTime() - start;
                outcome.rowsAffected += rowsAffected;
                for (GeneratedRow row : slice) {
                    outcome.recordedIds.add(idFactory.build(row, slice));
                }
            } catch (Exception ex) {
                if (config.detectConflicts && ConflictDetector.isConflict(config.engine, ex)) {
                    outcome.conflicts += slice.size();
                    log.warn(config.conflictLogFormat, config.logSubject, batchIndex, totalBatches, ex.getMessage());
                } else if (config.detectConflicts) {
                    throw ex;
                } else {
                    log.warn(config.failureLogFormat, config.logSubject, batchIndex, ex.getMessage());
                }
            }
            batchIndex++;
            ctx.progress().onBatch(node.entityName(), batchIndex, totalBatches, to, rows.size());
        }
        return outcome;
    }
}
