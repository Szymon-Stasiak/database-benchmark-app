package com.dbagnets.backend.engine.driver.sql;

import com.dbagnets.backend.engine.cascade.CascadeNode;
import com.dbagnets.backend.engine.datagen.GeneratedRow;
import com.dbagnets.backend.engine.driver.api.DeleteContext;
import com.dbagnets.backend.engine.driver.api.DeletionMode;
import com.dbagnets.backend.engine.driver.api.EngineDriver;
import com.dbagnets.backend.engine.driver.api.EntityOutcome;
import com.dbagnets.backend.engine.driver.api.InsertContext;
import com.dbagnets.backend.engine.driver.api.InsertMode;
import com.dbagnets.backend.engine.driver.api.ReadContext;
import com.dbagnets.backend.engine.driver.engines.pg.SqlCascadeDeleter;
import com.dbagnets.backend.engine.driver.support.BatchSizes;
import com.dbagnets.backend.engine.driver.support.ConflictDetector;
import com.dbagnets.backend.engine.driver.support.InsertAccumulator;
import com.dbagnets.backend.engine.registry.EntityIdRegistry.RegistryEntry;
import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalEntity;
import com.dbagnets.backend.engine.timing.RecordedId;
import com.dbagnets.backend.engine.timing.TimedOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class AbstractSqlDriver implements EngineDriver {

    private static final Logger log = LoggerFactory.getLogger(AbstractSqlDriver.class);

    protected abstract DataSource dataSource(String databaseId, String host, int port);

    protected abstract SqlCascadeDeleter.Dialect dialect();

    protected abstract SqlCascadeDeleter.Binder binder();

    protected abstract SqlInsertStatement buildInsertStatement(LogicalEntity entity);

    protected abstract String orphanConstraintSql();

    protected abstract String engineLogName();

    protected long fetchDescendantsForRead(Connection conn, com.dbagnets.backend.engine.schema.LogicalSchema schema, Object rootId, ReadContext ctx, LogicalEntity entity) throws SQLException {
        return 0L;
    }

    @Override
    public TimedOperation insert(InsertContext ctx) throws Exception {
        DataSource ds = dataSource(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        InsertAccumulator acc = new InsertAccumulator();
        long wireStart = System.nanoTime();
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            for (CascadeNode node : ctx.plan().nodesInInsertOrder()) {
                List<GeneratedRow> rows = ctx.rowsByEntity().get(node.entityName());
                if (rows == null || rows.isEmpty()) continue;
                LogicalEntity entity = ctx.schema().requireEntity(node.entityName());
                acc.accept(insertEntity(conn, entity, rows, ctx));
                ctx.progress().onEntityFinished(node.entityName());
            }
            conn.commit();
        }
        return acc.finish(System.nanoTime() - wireStart);
    }

    @Override
    public TimedOperation read(ReadContext ctx) throws Exception {
        DataSource ds = dataSource(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        LogicalEntity entity = ctx.schema().requireEntity(ctx.entityName());
        LogicalAttribute pk = entity.primaryKey().orElseThrow(() -> new IllegalStateException("Entity " + entity.name() + " has no primary key"));
        InsertMode mode = ctx.mode() == null ? InsertMode.SINGLE : ctx.mode();

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsRead = 0L;

        long wireStart = System.nanoTime();
        try (Connection conn = ds.getConnection()) {
            conn.setReadOnly(true);
            if (mode == InsertMode.BULK && !ctx.targets().isEmpty()) {
                List<Object> ids = ctx.targets().stream().map(t -> (Object) t.physicalId()).toList();
                long start = System.nanoTime();
                rowsRead = SqlCascadeDeleter.readBulk(conn, entity, pk, ids, dialect());
                long elapsedNs = System.nanoTime() - start;
                long perItem = elapsedNs / ctx.targets().size();
                Arrays.fill(samples, perItem);
                totalDbTimeNs = elapsedNs;
            } else {
                String table = dialect().quote().apply(entity.name().toLowerCase());
                String pkCol = dialect().quote().apply(pk.name().toLowerCase());
                String selectSql = "SELECT * FROM " + table + " WHERE " + pkCol + " = ?";
                try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                    for (int i = 0; i < ctx.targets().size(); i++) {
                        RegistryEntry entry = ctx.targets().get(i);
                        binder().bind(ps, 1, pk, entry.physicalId());
                        long start = System.nanoTime();
                        long n = SqlSupport.executeSelectCount(ps);
                        n += fetchDescendantsForRead(conn, ctx.schema(), entry.physicalId(), ctx, entity);
                        long sampleNs = System.nanoTime() - start;
                        samples[i] = sampleNs;
                        totalDbTimeNs += sampleNs;
                        rowsRead += n;
                    }
                }
            }
        }
        long wireTimeNs = System.nanoTime() - wireStart;

        return TimedOperation.builder().dbTimeNs(totalDbTimeNs).wireTimeNs(wireTimeNs).rowsAffected(rowsRead).sampleDbTimeNs(samples).build();
    }

    @Override
    public TimedOperation delete(DeleteContext ctx) throws Exception {
        DataSource ds = dataSource(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        LogicalEntity entity = ctx.schema().requireEntity(ctx.entityName());
        LogicalAttribute pk = entity.primaryKey().orElseThrow(() -> new IllegalStateException("Entity " + entity.name() + " has no primary key"));

        InsertMode mode = ctx.mode() == null ? InsertMode.SINGLE : ctx.mode();
        DeletionMode deletionMode = ctx.deletionMode() == null ? DeletionMode.NATIVE : ctx.deletionMode();
        boolean cascade = deletionMode == DeletionMode.WITH_CHILDREN;
        boolean orphan = deletionMode == DeletionMode.ROOT_ONLY;
        boolean forcePerRow = cascade || mode == InsertMode.SINGLE;

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsAffected = 0L;
        Map<String, List<String>> cascadeAccumulator = new LinkedHashMap<>();

        long wireStart = System.nanoTime();
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            if (orphan) {
                try (java.sql.Statement st = conn.createStatement()) {
                    st.execute(orphanConstraintSql());
                }
            }
            if (forcePerRow) {
                for (int i = 0; i < ctx.targets().size(); i++) {
                    RegistryEntry entry = ctx.targets().get(i);
                    long start = System.nanoTime();
                    int rootDeleted = 0;
                    try {
                        if (cascade) {
                            Map<String, List<Object>> cascaded = SqlCascadeDeleter.cascadeChildrenOf(conn, ctx.schema(), entity.name(), entry.physicalId(), dialect());
                            for (var e : cascaded.entrySet()) {
                                cascadeAccumulator.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue().stream().map(String::valueOf).toList());
                            }
                        }
                        rootDeleted = SqlCascadeDeleter.deleteRoot(conn, entity, pk, entry.physicalId(), dialect());
                        conn.commit();
                    } catch (SQLException ex) {
                        SqlSupport.safeRollback(conn);
                        log.warn("{} delete failed for {}/{}: {}", engineLogName(), entity.name(), entry.physicalId(), ex.getMessage());
                    }
                    long sampleNs = System.nanoTime() - start;
                    samples[i] = sampleNs;
                    totalDbTimeNs += sampleNs;
                    if (rootDeleted > 0) rowsAffected += rootDeleted;
                }
            } else {
                List<Object> ids = ctx.targets().stream().map(t -> (Object) t.physicalId()).toList();
                long start = System.nanoTime();
                int affected = 0;
                try {
                    affected = mode == InsertMode.BULK ? SqlCascadeDeleter.deleteRootBulk(conn, entity, pk, ids, dialect()) : SqlCascadeDeleter.deleteRootBatch(conn, entity, pk, ids, dialect());
                    conn.commit();
                } catch (SQLException ex) {
                    SqlSupport.safeRollback(conn);
                    log.warn("{} {} delete failed for {}: {}", engineLogName(), mode, entity.name(), ex.getMessage());
                }
                long elapsedNs = System.nanoTime() - start;
                long perItem = ctx.targets().isEmpty() ? 0 : elapsedNs / ctx.targets().size();
                Arrays.fill(samples, perItem);
                totalDbTimeNs = elapsedNs;
                rowsAffected = affected;
            }
        }
        long wireTimeNs = System.nanoTime() - wireStart;

        return TimedOperation.builder().dbTimeNs(totalDbTimeNs).wireTimeNs(wireTimeNs).rowsAffected(rowsAffected).sampleDbTimeNs(samples).cascadeDeletedByEntity(cascadeAccumulator).build();
    }

    protected EntityOutcome insertEntity(Connection conn, LogicalEntity entity, List<GeneratedRow> rows, InsertContext ctx) throws SQLException {
        SqlInsertStatement stmt = buildInsertStatement(entity);
        EntityOutcome outcome = new EntityOutcome();

        int batchSize = BatchSizes.effective(ctx, Integer.MAX_VALUE);
        int totalBatches = Math.max(1, (int) Math.ceil((double) rows.size() / batchSize));
        int batchIndex = 0;
        for (int from = 0; from < rows.size(); from += batchSize) {
            int to = Math.min(from + batchSize, rows.size());
            List<GeneratedRow> slice = rows.subList(from, to);
            try {
                BatchOutcome batchResult = executeBatchTimed(conn, stmt, slice, ctx.mode());
                outcome.rowsAffected += batchResult.rowsAffected();
                outcome.dbTimeNs += batchResult.dbTimeNs();
                slice.forEach(r -> outcome.recordedIds.add(new RecordedId(entity.name(), r.logicalId(), r.logicalId())));
            } catch (Exception ex) {
                if (ConflictDetector.isConflict(engine(), ex)) {
                    outcome.conflicts += slice.size();
                    log.warn("{} conflict on entity {} batch {}/{}: {}", engineLogName(), entity.name(), batchIndex, totalBatches, ex.getMessage());
                } else {
                    throw ex;
                }
            }
            batchIndex++;
            ctx.progress().onBatch(entity.name(), batchIndex, totalBatches, to, rows.size());
        }
        return outcome;
    }

    private BatchOutcome executeBatchTimed(Connection conn, SqlInsertStatement stmt, List<GeneratedRow> slice, InsertMode mode) throws SQLException {
        if (slice.isEmpty()) return new BatchOutcome(0L, 0L);
        return switch (mode) {
            case SINGLE -> singleTimed(conn, stmt, slice);
            case BATCH -> batchTimed(conn, stmt, slice);
            case BULK -> bulkTimed(conn, stmt, slice);
        };
    }

    private BatchOutcome singleTimed(Connection conn, SqlInsertStatement stmt, List<GeneratedRow> slice) throws SQLException {
        long dbTimeNs = 0L;
        long affected = 0L;
        try (PreparedStatement ps = conn.prepareStatement(stmt.singleRowSql())) {
            for (GeneratedRow row : slice) {
                bindRow(ps, stmt.orderedColumns(), row, 0);
                long start = System.nanoTime();
                int count = ps.executeUpdate();
                dbTimeNs += System.nanoTime() - start;
                if (count > 0) affected += count;
            }
        }
        return new BatchOutcome(dbTimeNs, affected);
    }

    private BatchOutcome batchTimed(Connection conn, SqlInsertStatement stmt, List<GeneratedRow> slice) throws SQLException {
        long affected = 0L;
        try (PreparedStatement ps = conn.prepareStatement(stmt.singleRowSql())) {
            for (GeneratedRow row : slice) {
                bindRow(ps, stmt.orderedColumns(), row, 0);
                ps.addBatch();
            }
            long start = System.nanoTime();
            int[] counts = ps.executeBatch();
            long dbTimeNs = System.nanoTime() - start;
            for (int c : counts) {
                if (c > 0) affected += c;
            }
            return new BatchOutcome(dbTimeNs, affected);
        }
    }

    private BatchOutcome bulkTimed(Connection conn, SqlInsertStatement stmt, List<GeneratedRow> slice) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(stmt.multiRowSql(slice.size()))) {
            int colCount = stmt.orderedColumns().size();
            for (int i = 0; i < slice.size(); i++) {
                bindRow(ps, stmt.orderedColumns(), slice.get(i), i * colCount);
            }
            long start = System.nanoTime();
            int count = ps.executeUpdate();
            long dbTimeNs = System.nanoTime() - start;
            return new BatchOutcome(dbTimeNs, Math.max(0, count));
        }
    }

    private void bindRow(PreparedStatement ps, List<LogicalAttribute> cols, GeneratedRow row, int offset) throws SQLException {
        for (int i = 0; i < cols.size(); i++) {
            LogicalAttribute attr = cols.get(i);
            binder().bind(ps, offset + i + 1, attr, row.get(attr.name()));
        }
    }

    private record BatchOutcome(long dbTimeNs, long rowsAffected) {
    }
}
