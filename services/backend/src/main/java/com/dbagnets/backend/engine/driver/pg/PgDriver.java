package com.dbagnets.backend.engine.driver.pg;

import com.dbagnets.backend.engine.driver.AbstractSqlDriver;
import com.dbagnets.backend.engine.driver.FrontierBfs;
import com.dbagnets.backend.engine.driver.ReadContext;
import com.dbagnets.backend.engine.driver.ReadDepth;
import com.dbagnets.backend.engine.driver.ScenarioTimings;
import com.dbagnets.backend.engine.scenario.AggregateParams;
import com.dbagnets.backend.engine.scenario.KnnParams;
import com.dbagnets.backend.engine.scenario.RangeParams;
import com.dbagnets.backend.engine.scenario.ResultCanonicalizer;
import com.dbagnets.backend.engine.scenario.ScenarioContext;
import com.dbagnets.backend.engine.scenario.TraversalParams;
import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalEntity;
import com.dbagnets.backend.engine.schema.LogicalSchema;
import com.dbagnets.backend.domain.DatabaseEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PgDriver extends AbstractSqlDriver {

    private static final SqlCascadeDeleter.Dialect DIALECT = new SqlCascadeDeleter.Dialect(
            ident -> "\"" + ident.replace("\"", "\"\"") + "\"",
            PgValueBinder::bind);

    private final PgDataSourceCache dataSources;

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.POSTGRESQL;
    }

    protected PgConnectionInfo connectionInfo(String databaseId, String host, int port) {
        return PgConnectionInfo.defaultLocal(databaseId, host, port);
    }

    protected PgInsertStatement buildInsertStatement(LogicalEntity entity) {
        return PgInsertStatement.of(entity);
    }

    @Override
    protected DataSource dataSource(String databaseId, String host, int port) {
        return dataSources.get(connectionInfo(databaseId, host, port));
    }

    @Override
    protected SqlCascadeDeleter.Dialect dialect() {
        return DIALECT;
    }

    @Override
    protected SqlCascadeDeleter.Binder binder() {
        return PgValueBinder::bind;
    }

    @Override
    protected String orphanConstraintSql() {
        return "SET session_replication_role = replica";
    }

    @Override
    protected String engineLogName() {
        return "PG";
    }

    @Override
    protected long fetchDescendantsForRead(Connection conn, LogicalSchema schema, Object rootId, ReadContext ctx, LogicalEntity entity) throws SQLException {
        ReadDepth depth = ctx.readDepth() == null ? ReadDepth.NONE : ctx.readDepth();
        List<PgScenarios.TraversalLevel> chain = childChainForRead(schema, entity.name(), depth);
        if (chain.isEmpty()) return 0L;
        return fetchDescendants(conn, schema, rootId, chain);
    }

    @Override
    public ScenarioOutcome runScenario(ScenarioContext ctx) throws Exception {
        DataSource ds = dataSource(ctx.databaseId(), ctx.hostAddress(), ctx.hostPort());
        try (Connection conn = ds.getConnection()) {
            conn.setReadOnly(true);
            return ScenarioTimings.execute(() -> switch (ctx.params()) {
                case AggregateParams p -> {
                    var grouped = PgScenarios.executeAggregate(conn, ctx.schema(), p.parentEntity(), p.childEntity());
                    yield ResultCanonicalizer.build(grouped, grouped.size());
                }
                case RangeParams p -> {
                    long count = PgScenarios.executeRangeCount(conn, ctx.schema(), p.entityName(), p.attribute(), p.min(), p.max());
                    yield ResultCanonicalizer.build(java.util.Map.of("count", count), count);
                }
                case TraversalParams p -> {
                    var ids = PgScenarios.executeTraversal(conn, ctx.schema(), p.startEntity(), p.startLogicalId(), p.depth());
                    yield ResultCanonicalizer.build(ids, ids.size());
                }
                case KnnParams ignored ->
                        throw new UnsupportedOperationException(engine() + " does not support VECTOR_KNN");
            });
        }
    }

    private List<PgScenarios.TraversalLevel> childChainForRead(LogicalSchema schema, String entityName, ReadDepth depth) {
        if (depth == ReadDepth.NONE) return List.of();
        if (depth == ReadDepth.ONE_HOP) return PgScenarios.resolveChain(schema, entityName, 1);
        return PgScenarios.resolveChain(schema, entityName, ReadDepth.FULL_CASCADE_MAX_DEPTH);
    }

    private long fetchDescendants(Connection conn, LogicalSchema schema, Object rootId, List<PgScenarios.TraversalLevel> chain) throws SQLException {
        return FrontierBfs.descendSql(chain, rootId, (level, frontier, nextFrontier) -> {
            LogicalEntity childEntity = schema.requireEntity(level.childEntity());
            LogicalAttribute childPk = childEntity.primaryKey().orElseThrow(() -> new IllegalStateException(level.childEntity() + " has no PK"));
            String table = "\"" + childEntity.name().toLowerCase() + "\"";
            String pkCol = "\"" + childPk.name().toLowerCase() + "\"";
            String fkCol = "\"" + level.fkColumn().toLowerCase() + "\"";
            String inList = String.join(",", java.util.Collections.nCopies(frontier.size(), "?"));
            String sql = "SELECT " + pkCol + ", * FROM " + table + " WHERE " + fkCol + " IN (" + inList + ")";
            long count = 0L;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < frontier.size(); i++) {
                    PgValueBinder.bind(ps, i + 1, childPk, frontier.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        count++;
                        Object id = rs.getObject(1);
                        if (id != null) nextFrontier.add(id);
                    }
                }
            }
            return count;
        });
    }
}
