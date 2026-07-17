package com.dbagnets.backend.engine.driver.pg;

import com.dbagnets.backend.engine.cascade.CascadeNode;
import com.dbagnets.backend.engine.datagen.GeneratedRow;
import com.dbagnets.backend.engine.driver.ConflictDetector;
import com.dbagnets.backend.engine.driver.DeleteContext;
import com.dbagnets.backend.engine.driver.EngineDriver;
import com.dbagnets.backend.engine.driver.InsertContext;
import com.dbagnets.backend.engine.driver.InsertMode;
import com.dbagnets.backend.engine.driver.ReadContext;
import com.dbagnets.backend.engine.driver.ReadDepth;
import com.dbagnets.backend.engine.registry.EntityIdRegistry.RegistryEntry;
import com.dbagnets.backend.engine.scenario.AggregateParams;
import com.dbagnets.backend.engine.scenario.KnnParams;
import com.dbagnets.backend.engine.scenario.RangeParams;
import com.dbagnets.backend.engine.scenario.ResultCanonicalizer;
import com.dbagnets.backend.engine.scenario.ScenarioContext;
import com.dbagnets.backend.engine.scenario.ScenarioResult;
import com.dbagnets.backend.engine.scenario.TraversalParams;
import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalEntity;
import com.dbagnets.backend.engine.timing.RecordedId;
import com.dbagnets.backend.engine.timing.TimedOperation;
import com.dbagnets.backend.domain.DatabaseEngine;
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
public class PgDriver implements EngineDriver {

    private final PgDataSourceCache dataSources;

    private static final SqlCascadeDeleter.Dialect DIALECT = new SqlCascadeDeleter.Dialect(
            ident -> "\"" + ident.replace("\"", "\"\"") + "\"",
            PgValueBinder::bind);

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.POSTGRESQL;
    }

    private static void safeRollback(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
        }
    }

    protected PgConnectionInfo connectionInfo(String databaseId, String host, int port) {
        return PgConnectionInfo.defaultLocal(databaseId, host, port);
    }

    protected PgInsertStatement buildInsertStatement(LogicalEntity entity) {
        return PgInsertStatement.of(entity);
    }

    @Override
    public TimedOperation insert(InsertContext ctx) throws Exception {
        PgConnectionInfo info = connectionInfo(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
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
        PgConnectionInfo info = connectionInfo(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        DataSource ds = dataSources.get(info);
        LogicalEntity entity = ctx.schema().requireEntity(ctx.entityName());
        LogicalAttribute pk = entity.primaryKey()
                .orElseThrow(() -> new IllegalStateException("Entity " + entity.name() + " has no primary key"));

        InsertMode mode = ctx.mode() == null ? InsertMode.SINGLE : ctx.mode();
        ReadDepth depth = ctx.readDepth() == null ? ReadDepth.NONE : ctx.readDepth();

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsRead = 0L;

        long wireStart = System.nanoTime();
        try (Connection conn = ds.getConnection()) {
            conn.setReadOnly(true);
            if (depth == ReadDepth.NONE && mode == InsertMode.BULK && !ctx.targets().isEmpty()) {
                List<Object> ids = ctx.targets().stream().map(t -> (Object) t.physicalId()).toList();
                long start = System.nanoTime();
                rowsRead = SqlCascadeDeleter.readBulk(conn, entity, pk, ids, DIALECT);
                long elapsedNs = System.nanoTime() - start;
                long perItem = elapsedNs / ctx.targets().size();
                for (int i = 0; i < samples.length; i++) samples[i] = perItem;
                totalDbTimeNs = elapsedNs;
            } else {
                String table = "\"" + entity.name().toLowerCase() + "\"";
                String pkCol = "\"" + pk.name().toLowerCase() + "\"";
                String selectSql = "SELECT * FROM " + table + " WHERE " + pkCol + " = ?";
                List<PgScenarios.TraversalLevel> chain = childChainForRead(ctx.schema(), entity.name(), depth);
                try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                    for (int i = 0; i < ctx.targets().size(); i++) {
                        RegistryEntry entry = ctx.targets().get(i);
                        PgValueBinder.bind(ps, 1, pk, entry.physicalId());
                        long start = System.nanoTime();
                        long n = executeSelect(ps);
                        if (!chain.isEmpty()) {
                            n += fetchDescendants(conn, ctx.schema(), entry.physicalId(), chain);
                        }
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
        PgConnectionInfo info = connectionInfo(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        DataSource ds = dataSources.get(info);
        LogicalEntity entity = ctx.schema().requireEntity(ctx.entityName());
        LogicalAttribute pk = entity.primaryKey()
                .orElseThrow(() -> new IllegalStateException("Entity " + entity.name() + " has no primary key"));

        InsertMode mode = ctx.mode() == null ? InsertMode.SINGLE : ctx.mode();
        com.dbagnets.backend.engine.driver.DeletionMode deletionMode =
                ctx.deletionMode() == null ? com.dbagnets.backend.engine.driver.DeletionMode.NATIVE : ctx.deletionMode();
        boolean cascade = deletionMode == com.dbagnets.backend.engine.driver.DeletionMode.WITH_CHILDREN;
        boolean orphan = deletionMode == com.dbagnets.backend.engine.driver.DeletionMode.ROOT_ONLY;
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
                    st.execute("SET session_replication_role = replica");
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
                        log.warn("PG delete failed for {}/{}: {}", entity.name(), entry.physicalId(), ex.getMessage());
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
                    log.warn("PG {} delete failed for {}: {}", mode, entity.name(), ex.getMessage());
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

    @Override
    public ScenarioOutcome runScenario(ScenarioContext ctx) throws Exception {
        PgConnectionInfo info = connectionInfo(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        DataSource ds = dataSources.get(info);

        long wireStart = System.nanoTime();
        long dbTimeNs;
        ScenarioResult result;
        try (Connection conn = ds.getConnection()) {
            conn.setReadOnly(true);
            long start = System.nanoTime();
            result = switch (ctx.params()) {
                case AggregateParams p -> {
                    var grouped = PgScenarios.executeAggregate(conn, ctx.schema(), p.parentEntity(), p.childEntity());
                    yield ResultCanonicalizer.build(grouped, grouped.size());
                }
                case RangeParams p -> {
                    long count = PgScenarios.executeRangeCount(conn, ctx.schema(), p.entityName(),
                            p.attribute(), p.min(), p.max());
                    yield ResultCanonicalizer.build(java.util.Map.of("count", count), count);
                }
                case TraversalParams p -> {
                    var ids = PgScenarios.executeTraversal(conn, ctx.schema(), p.startEntity(),
                            p.startLogicalId(), p.depth());
                    yield ResultCanonicalizer.build(ids, ids.size());
                }
                case KnnParams ignored -> throw new UnsupportedOperationException(
                        engine() + " does not support VECTOR_KNN");
            };
            dbTimeNs = System.nanoTime() - start;
        }
        long wireTimeNs = System.nanoTime() - wireStart;

        TimedOperation timed = TimedOperation.builder()
                .dbTimeNs(dbTimeNs)
                .wireTimeNs(wireTimeNs)
                .rowsAffected(result.rowsReturned())
                .sampleDbTimeNs(new long[] { dbTimeNs })
                .build();
        return new ScenarioOutcome(timed, result);
    }

    private long executeSelect(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            long n = 0L;
            while (rs.next()) n++;
            return n;
        }
    }

    private List<PgScenarios.TraversalLevel> childChainForRead(com.dbagnets.backend.engine.schema.LogicalSchema schema,
                                                                  String entityName,
                                                                  ReadDepth depth) {
        if (depth == ReadDepth.NONE) return List.of();
        if (depth == ReadDepth.ONE_HOP) return PgScenarios.resolveChain(schema, entityName, 1);
        return PgScenarios.resolveChain(schema, entityName, ReadDepth.FULL_CASCADE_MAX_DEPTH);
    }

    private long fetchDescendants(Connection conn,
                                    com.dbagnets.backend.engine.schema.LogicalSchema schema,
                                    Object rootId,
                                    List<PgScenarios.TraversalLevel> chain) throws SQLException {
        long total = 0L;
        List<Object> frontier = List.of(rootId);
        for (PgScenarios.TraversalLevel level : chain) {
            if (frontier.isEmpty()) break;
            LogicalEntity childEntity = schema.requireEntity(level.childEntity());
            LogicalAttribute childPk = childEntity.primaryKey()
                    .orElseThrow(() -> new IllegalStateException(level.childEntity() + " has no PK"));
            String table = "\"" + childEntity.name().toLowerCase() + "\"";
            String pkCol = "\"" + childPk.name().toLowerCase() + "\"";
            String fkCol = "\"" + level.fkColumn().toLowerCase() + "\"";
            String inList = String.join(",", java.util.Collections.nCopies(frontier.size(), "?"));
            String sql = "SELECT " + pkCol + ", * FROM " + table + " WHERE " + fkCol + " IN (" + inList + ")";
            List<Object> nextFrontier = new java.util.ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < frontier.size(); i++) {
                    PgValueBinder.bind(ps, i + 1, childPk, frontier.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        total++;
                        Object id = rs.getObject(1);
                        if (id != null) nextFrontier.add(id);
                    }
                }
            }
            frontier = nextFrontier;
        }
        return total;
    }

    private EntityInsertOutcome insertEntity(Connection conn,
                                              LogicalEntity entity,
                                              List<GeneratedRow> rows,
                                              InsertContext ctx) throws SQLException {
        PgInsertStatement stmt = buildInsertStatement(entity);
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
                    log.warn("PG conflict on entity {} batch {}/{}: {}", entity.name(), batchIndex, totalBatches, ex.getMessage());
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
                                            PgInsertStatement stmt,
                                            List<GeneratedRow> slice,
                                            InsertMode mode) throws SQLException {
        if (slice.isEmpty()) return new BatchOutcome(0L, 0L);
        return switch (mode) {
            case SINGLE -> singleTimed(conn, stmt, slice);
            case BATCH -> batchTimed(conn, stmt, slice);
            case BULK -> bulkTimed(conn, stmt, slice);
        };
    }

    private BatchOutcome singleTimed(Connection conn, PgInsertStatement stmt, List<GeneratedRow> slice) throws SQLException {
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

    private BatchOutcome batchTimed(Connection conn, PgInsertStatement stmt, List<GeneratedRow> slice) throws SQLException {
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

    private BatchOutcome bulkTimed(Connection conn, PgInsertStatement stmt, List<GeneratedRow> slice) throws SQLException {
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
            PgValueBinder.bind(ps, offset + i + 1, attr, row.get(attr.name()));
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
