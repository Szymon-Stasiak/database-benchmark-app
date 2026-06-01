package com.dbagnets.backend.benchmark.driver.pg;

import com.dbagnets.backend.benchmark.cascade.CascadeNode;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PgDriver implements EngineDriver {

    private final PgDataSourceCache dataSources;
    private final ObjectMapper objectMapper;

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.POSTGRESQL;
    }

    @Override
    public TimedOperation insert(InsertContext ctx) throws Exception {
        PgConnectionInfo info = PgConnectionInfo.defaultLocal(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
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
        PgConnectionInfo info = PgConnectionInfo.defaultLocal(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        DataSource ds = dataSources.get(info);
        LogicalEntity entity = ctx.schema().requireEntity(ctx.entityName());
        LogicalAttribute pk = entity.primaryKey()
                .orElseThrow(() -> new IllegalStateException("Entity " + entity.name() + " has no primary key"));
        String table = "\"" + entity.name().toLowerCase() + "\"";
        String pkCol = "\"" + pk.name().toLowerCase() + "\"";
        String baseSelect = "SELECT * FROM " + table + " WHERE " + pkCol + " = ?";
        String explainSql = "EXPLAIN (ANALYZE, FORMAT JSON, BUFFERS) " + baseSelect;

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsRead = 0L;

        long wireStart = System.nanoTime();
        try (Connection conn = ds.getConnection()) {
            conn.setReadOnly(true);
            for (int i = 0; i < ctx.targets().size(); i++) {
                RegistryEntry entry = ctx.targets().get(i);
                long sampleNs = explainSelect(conn, explainSql, pk, entry.physicalId());
                samples[i] = sampleNs;
                totalDbTimeNs += sampleNs;
                rowsRead += executeSelect(conn, baseSelect, pk, entry.physicalId());
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
        PgConnectionInfo info = PgConnectionInfo.defaultLocal(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        DataSource ds = dataSources.get(info);
        LogicalEntity entity = ctx.schema().requireEntity(ctx.entityName());
        LogicalAttribute pk = entity.primaryKey()
                .orElseThrow(() -> new IllegalStateException("Entity " + entity.name() + " has no primary key"));
        String table = "\"" + entity.name().toLowerCase() + "\"";
        String pkCol = "\"" + pk.name().toLowerCase() + "\"";
        String baseDelete = "DELETE FROM " + table + " WHERE " + pkCol + " = ?";
        String explainSql = "EXPLAIN (ANALYZE, FORMAT JSON, BUFFERS) " + baseDelete;

        long[] samples = new long[ctx.targets().size()];
        long totalDbTimeNs = 0L;
        long rowsAffected = 0L;

        long wireStart = System.nanoTime();
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            for (int i = 0; i < ctx.targets().size(); i++) {
                RegistryEntry entry = ctx.targets().get(i);
                ExplainDeleteOutcome outcome = explainDelete(conn, explainSql, pk, entry.physicalId());
                samples[i] = outcome.dbTimeNs;
                totalDbTimeNs += outcome.dbTimeNs;
                rowsAffected += outcome.rowsAffected;
            }
            conn.commit();
        }
        long wireTimeNs = System.nanoTime() - wireStart;

        return TimedOperation.builder()
                .dbTimeNs(totalDbTimeNs)
                .wireTimeNs(wireTimeNs)
                .rowsAffected(rowsAffected)
                .sampleDbTimeNs(samples)
                .build();
    }

    private ExplainDeleteOutcome explainDelete(Connection conn, String explainSql, LogicalAttribute pk, String physicalId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(explainSql)) {
            PgValueBinder.bind(ps, 1, pk, physicalId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new ExplainDeleteOutcome(0L, 0L);
                String json = rs.getString(1);
                JsonNode root = objectMapper.readTree(json);
                if (root.isArray() && root.size() > 0) {
                    JsonNode plan = root.get(0);
                    JsonNode execTime = plan.path("Execution Time");
                    long dbTimeNs = execTime.isNumber() ? (long) (execTime.asDouble() * 1_000_000.0) : 0L;
                    long affected = plan.path("Plan").path("Actual Rows").asLong(0L);
                    return new ExplainDeleteOutcome(dbTimeNs, affected);
                }
            } catch (Exception e) {
                log.debug("PG EXPLAIN DELETE parse failed: {}", e.getMessage());
            }
        }
        return new ExplainDeleteOutcome(0L, 0L);
    }

    private record ExplainDeleteOutcome(long dbTimeNs, long rowsAffected) {
    }

    private long explainSelect(Connection conn, String explainSql, LogicalAttribute pk, String physicalId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(explainSql)) {
            PgValueBinder.bind(ps, 1, pk, physicalId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return 0L;
                String json = rs.getString(1);
                JsonNode root = objectMapper.readTree(json);
                if (root.isArray() && root.size() > 0) {
                    JsonNode execTime = root.get(0).path("Execution Time");
                    if (execTime.isNumber()) return (long) (execTime.asDouble() * 1_000_000.0);
                }
            } catch (Exception e) {
                log.debug("PG EXPLAIN parse failed: {}", e.getMessage());
            }
        }
        return 0L;
    }

    private long executeSelect(Connection conn, String sql, LogicalAttribute pk, String physicalId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            PgValueBinder.bind(ps, 1, pk, physicalId);
            try (ResultSet rs = ps.executeQuery()) {
                long n = 0L;
                while (rs.next()) n++;
                return n;
            }
        }
    }

    private EntityInsertOutcome insertEntity(Connection conn,
                                              LogicalEntity entity,
                                              List<GeneratedRow> rows,
                                              InsertContext ctx) throws SQLException {
        PgInsertStatement stmt = PgInsertStatement.of(entity);
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
        String sql = "EXPLAIN (ANALYZE, FORMAT JSON, BUFFERS) " + stmt.singleRowSql() + " RETURNING 1";
        long dbTimeNs = 0L;
        long affected = 0L;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (GeneratedRow row : slice) {
                bindRow(ps, stmt.orderedColumns(), row, 0);
                ExplainOutcome parsed = runExplain(ps);
                dbTimeNs += parsed.dbTimeNs();
                affected += parsed.rowsAffected();
            }
        }
        return new BatchOutcome(dbTimeNs, affected);
    }

    private BatchOutcome batchTimed(Connection conn, PgInsertStatement stmt, List<GeneratedRow> slice) throws SQLException {
        long t0Ns = serverClockNanos(conn);
        long affected = 0L;
        try (PreparedStatement ps = conn.prepareStatement(stmt.singleRowSql())) {
            for (GeneratedRow row : slice) {
                bindRow(ps, stmt.orderedColumns(), row, 0);
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            for (int c : counts) {
                if (c > 0) affected += c;
            }
        }
        long t1Ns = serverClockNanos(conn);
        return new BatchOutcome(Math.max(0L, t1Ns - t0Ns), affected);
    }

    private BatchOutcome bulkTimed(Connection conn, PgInsertStatement stmt, List<GeneratedRow> slice) throws SQLException {
        String sql = "EXPLAIN (ANALYZE, FORMAT JSON, BUFFERS) " + stmt.multiRowSql(slice.size()) + " RETURNING 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int colCount = stmt.orderedColumns().size();
            for (int i = 0; i < slice.size(); i++) {
                bindRow(ps, stmt.orderedColumns(), slice.get(i), i * colCount);
            }
            ExplainOutcome parsed = runExplain(ps);
            return new BatchOutcome(parsed.dbTimeNs(), parsed.rowsAffected());
        }
    }

    private ExplainOutcome runExplain(PreparedStatement ps) {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return new ExplainOutcome(0L, 0L);
            String json = rs.getString(1);
            JsonNode root = objectMapper.readTree(json);
            if (root.isArray() && root.size() > 0) {
                JsonNode plan = root.get(0);
                JsonNode execTime = plan.path("Execution Time");
                long dbTimeNs = execTime.isNumber() ? (long) (execTime.asDouble() * 1_000_000.0) : 0L;
                long affected = plan.path("Plan").path("Actual Rows").asLong(0L);
                return new ExplainOutcome(dbTimeNs, affected);
            }
        } catch (Exception e) {
            log.debug("PG EXPLAIN run/parse failed: {}", e.getMessage());
        }
        return new ExplainOutcome(0L, 0L);
    }

    private long serverClockNanos(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT EXTRACT(EPOCH FROM clock_timestamp())")) {
            return rs.next() ? (long) (rs.getDouble(1) * 1_000_000_000.0) : 0L;
        }
    }

    private void bindRow(PreparedStatement ps, List<LogicalAttribute> cols, GeneratedRow row, int offset) throws SQLException {
        for (int i = 0; i < cols.size(); i++) {
            LogicalAttribute attr = cols.get(i);
            PgValueBinder.bind(ps, offset + i + 1, attr, row.get(attr.name()));
        }
    }

    private record BatchOutcome(long dbTimeNs, long rowsAffected) {}

    private record ExplainOutcome(long dbTimeNs, long rowsAffected) {}

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
