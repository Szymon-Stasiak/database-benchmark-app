package com.dbagnets.backend.benchmark.driver.mysql;

import com.dbagnets.backend.benchmark.cascade.CascadeNode;
import com.dbagnets.backend.benchmark.driver.pg.SqlCascadeDeleter;
import com.dbagnets.backend.benchmark.datagen.GeneratedRow;
import com.dbagnets.backend.benchmark.driver.ConflictDetector;
import com.dbagnets.backend.benchmark.driver.DeleteContext;
import com.dbagnets.backend.benchmark.driver.EngineDriver;
import com.dbagnets.backend.benchmark.driver.InsertContext;
import com.dbagnets.backend.benchmark.driver.InsertMode;
import com.dbagnets.backend.benchmark.driver.ReadContext;
import com.dbagnets.backend.benchmark.registry.EntityIdRegistry.RegistryEntry;
import com.dbagnets.backend.benchmark.schema.LogicalAttribute;
import com.dbagnets.backend.benchmark.schema.LogicalEntity;
import com.dbagnets.backend.benchmark.timing.RecordedId;
import com.dbagnets.backend.benchmark.timing.TimedOperation;
import com.dbagnets.backend.entity.DatabaseEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MysqlDriver implements EngineDriver {

    private final MysqlDataSourceCache dataSources;

    private static final SqlCascadeDeleter.Dialect DIALECT = new SqlCascadeDeleter.Dialect(
            ident -> "`" + ident.replace("`", "``") + "`",
            MysqlValueBinder::bind);

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.MYSQL;
    }

    private static void safeRollback(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
        }
    }

    @Override
    public TimedOperation insert(InsertContext ctx) throws Exception {
        MysqlConnectionInfo info = MysqlConnectionInfo.defaultLocal(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        DataSource ds = dataSources.get(info);

        long totalDbTimeNs = 0L;
        long totalRowsAffected = 0L;
        int totalConflicts = 0;
        List<RecordedId> recordedIds = new ArrayList<>();

        long wireStart = System.nanoTime();
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            for (CascadeNode node : ctx.plan().nodesInInsertOrder()) {
                List<GeneratedRow> rows = ctx.rowsByEntity().get(node.entityName());
                if (rows == null || rows.isEmpty()) continue;
                LogicalEntity entity = ctx.schema().requireEntity(node.entityName());
                EntityInsertOutcome outcome = insertEntity(conn, entity, rows, ctx);
                totalDbTimeNs += outcome.dbTimeNs;
                totalRowsAffected += outcome.rowsAffected;
                totalConflicts += outcome.conflicts;
                recordedIds.addAll(outcome.recordedIds);
                ctx.progress().onEntityFinished(node.entityName());
            }
            conn.commit();
        }
        long wireTimeNs = System.nanoTime() - wireStart;

        return TimedOperation.builder()
                .dbTimeNs(totalDbTimeNs)
                .wireTimeNs(wireTimeNs)
                .rowsAffected(totalRowsAffected)
                .conflictsSkipped(totalConflicts)
                .recordedIds(recordedIds)
                .build();
    }

    @Override
    public TimedOperation read(ReadContext ctx) throws Exception {
        MysqlConnectionInfo info = MysqlConnectionInfo.defaultLocal(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        DataSource ds = dataSources.get(info);
        LogicalEntity entity = ctx.schema().requireEntity(ctx.entityName());
        LogicalAttribute pk = entity.primaryKey()
                .orElseThrow(() -> new IllegalStateException("Entity " + entity.name() + " has no primary key"));

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
                rowsRead = SqlCascadeDeleter.readBulk(conn, entity, pk, ids, DIALECT);
                long elapsedNs = System.nanoTime() - start;
                long perItem = elapsedNs / ctx.targets().size();
                for (int i = 0; i < samples.length; i++) samples[i] = perItem;
                totalDbTimeNs = elapsedNs;
            } else {
                String table = "`" + entity.name().toLowerCase() + "`";
                String pkCol = "`" + pk.name().toLowerCase() + "`";
                String selectSql = "SELECT * FROM " + table + " WHERE " + pkCol + " = ?";
                try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                    for (int i = 0; i < ctx.targets().size(); i++) {
                        RegistryEntry entry = ctx.targets().get(i);
                        MysqlValueBinder.bind(ps, 1, pk, entry.physicalId());
                        long start = System.nanoTime();
                        long n = executeSelect(ps);
                        long sampleNs = System.nanoTime() - start;
                        samples[i] = sampleNs;
                        totalDbTimeNs += sampleNs;
                        rowsRead += n;
                    }
                }
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
    public TimedOperation delete(DeleteContext ctx) throws Exception {
        MysqlConnectionInfo info = MysqlConnectionInfo.defaultLocal(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        DataSource ds = dataSources.get(info);
        LogicalEntity entity = ctx.schema().requireEntity(ctx.entityName());
        LogicalAttribute pk = entity.primaryKey()
                .orElseThrow(() -> new IllegalStateException("Entity " + entity.name() + " has no primary key"));

        InsertMode mode = ctx.mode() == null ? InsertMode.SINGLE : ctx.mode();
        com.dbagnets.backend.benchmark.driver.DeletionMode deletionMode =
                ctx.deletionMode() == null ? com.dbagnets.backend.benchmark.driver.DeletionMode.NATIVE : ctx.deletionMode();
        boolean cascade = deletionMode == com.dbagnets.backend.benchmark.driver.DeletionMode.WITH_CHILDREN;
        boolean orphan = deletionMode == com.dbagnets.backend.benchmark.driver.DeletionMode.ROOT_ONLY;
        boolean forcePerRow = cascade || mode == InsertMode.SINGLE;

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsAffected = 0L;
        java.util.Map<String, java.util.List<String>> cascadeAccumulator = new java.util.LinkedHashMap<>();

        long wireStart = System.nanoTime();
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            if (orphan) {
                try (java.sql.Statement st = conn.createStatement()) {
                    st.execute("SET FOREIGN_KEY_CHECKS=0");
                }
            }
            if (forcePerRow) {
                for (int i = 0; i < ctx.targets().size(); i++) {
                    RegistryEntry entry = ctx.targets().get(i);
                    long start = System.nanoTime();
                    int rootDeleted = 0;
                    try {
                        if (cascade) {
                            java.util.Map<String, java.util.List<Object>> cascaded =
                                    SqlCascadeDeleter.cascadeChildrenOf(conn, ctx.schema(), entity.name(), entry.physicalId(), DIALECT);
                            for (var e : cascaded.entrySet()) {
                                cascadeAccumulator
                                        .computeIfAbsent(e.getKey(), k -> new java.util.ArrayList<>())
                                        .addAll(e.getValue().stream().map(String::valueOf).toList());
                            }
                        }
                        rootDeleted = SqlCascadeDeleter.deleteRoot(conn, entity, pk, entry.physicalId(), DIALECT);
                        conn.commit();
                    } catch (SQLException ex) {
                        safeRollback(conn);
                        log.warn("MySQL delete failed for {}/{}: {}", entity.name(), entry.physicalId(), ex.getMessage());
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
                    affected = mode == InsertMode.BULK
                            ? SqlCascadeDeleter.deleteRootBulk(conn, entity, pk, ids, DIALECT)
                            : SqlCascadeDeleter.deleteRootBatch(conn, entity, pk, ids, DIALECT);
                    conn.commit();
                } catch (SQLException ex) {
                    safeRollback(conn);
                    log.warn("MySQL {} delete failed for {}: {}", mode, entity.name(), ex.getMessage());
                }
                long elapsedNs = System.nanoTime() - start;
                long perItem = ctx.targets().isEmpty() ? 0 : elapsedNs / ctx.targets().size();
                for (int i = 0; i < samples.length; i++) samples[i] = perItem;
                totalDbTimeNs = elapsedNs;
                rowsAffected = affected;
            }
        }
        long wireTimeNs = System.nanoTime() - wireStart;

        return TimedOperation.builder()
                .dbTimeNs(totalDbTimeNs)
                .wireTimeNs(wireTimeNs)
                .rowsAffected(rowsAffected)
                .sampleDbTimeNs(samples)
                .cascadeDeletedByEntity(cascadeAccumulator)
                .build();
    }

    private long executeSelect(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            long n = 0L;
            while (rs.next()) n++;
            return n;
        }
    }

    private EntityInsertOutcome insertEntity(Connection conn,
                                              LogicalEntity entity,
                                              List<GeneratedRow> rows,
                                              InsertContext ctx) throws SQLException {
        MysqlInsertStatement stmt = MysqlInsertStatement.of(entity);
        EntityInsertOutcome outcome = new EntityInsertOutcome();

        int batchSize = effectiveBatchSize(ctx);
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
                    log.warn("MySQL conflict on entity {} batch {}/{}: {}", entity.name(), batchIndex, totalBatches, ex.getMessage());
                } else {
                    throw ex;
                }
            }
            batchIndex++;
            ctx.progress().onBatch(entity.name(), batchIndex, totalBatches, to, rows.size());
        }
        return outcome;
    }

    private BatchOutcome executeBatchTimed(Connection conn,
                                            MysqlInsertStatement stmt,
                                            List<GeneratedRow> slice,
                                            InsertMode mode) throws SQLException {
        if (slice.isEmpty()) return new BatchOutcome(0L, 0L);
        return switch (mode) {
            case SINGLE -> singleTimed(conn, stmt, slice);
            case BATCH -> batchTimed(conn, stmt, slice);
            case BULK -> bulkTimed(conn, stmt, slice);
        };
    }

    private BatchOutcome singleTimed(Connection conn, MysqlInsertStatement stmt, List<GeneratedRow> slice) throws SQLException {
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

    private BatchOutcome batchTimed(Connection conn, MysqlInsertStatement stmt, List<GeneratedRow> slice) throws SQLException {
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

    private BatchOutcome bulkTimed(Connection conn, MysqlInsertStatement stmt, List<GeneratedRow> slice) throws SQLException {
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
            MysqlValueBinder.bind(ps, offset + i + 1, attr, row.get(attr.name()));
        }
    }

    private record BatchOutcome(long dbTimeNs, long rowsAffected) {}

    private int effectiveBatchSize(InsertContext ctx) {
        return switch (ctx.mode()) {
            case SINGLE -> 1;
            case BATCH -> Math.max(1, ctx.batchSize());
            case BULK -> Math.max(1, ctx.batchSize() > 0 ? ctx.batchSize() : Integer.MAX_VALUE);
        };
    }

    private static final class EntityInsertOutcome {
        long dbTimeNs;
        long rowsAffected;
        int conflicts;
        List<RecordedId> recordedIds = new ArrayList<>();
    }
}
