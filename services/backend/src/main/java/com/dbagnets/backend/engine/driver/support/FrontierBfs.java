package com.dbagnets.backend.engine.driver.support;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.dbagnets.backend.engine.driver.engines.pg.PgScenarios;

public final class FrontierBfs {

    private FrontierBfs() {}

    @FunctionalInterface
    public interface LevelHandler {
        long execute(
                PgScenarios.TraversalLevel level, List<Object> frontier, List<Object> nextFrontier);
    }

    @FunctionalInterface
    public interface SqlLevelHandler {
        long execute(
                PgScenarios.TraversalLevel level, List<Object> frontier, List<Object> nextFrontier)
                throws SQLException;
    }

    public static long descend(
            List<PgScenarios.TraversalLevel> chain, Object rootId, LevelHandler handler) {
        try {
            return doDescend(chain, rootId, handler::execute);
        } catch (SQLException e) {
            throw new AssertionError("unreachable", e);
        }
    }

    public static long descendSql(
            List<PgScenarios.TraversalLevel> chain, Object rootId, SqlLevelHandler handler)
            throws SQLException {
        return doDescend(chain, rootId, handler);
    }

    private static long doDescend(
            List<PgScenarios.TraversalLevel> chain, Object rootId, SqlLevelHandler handler)
            throws SQLException {
        long total = 0L;
        List<Object> frontier = List.of(rootId);
        for (PgScenarios.TraversalLevel level : chain) {
            if (frontier.isEmpty()) break;
            List<Object> nextFrontier = new ArrayList<>();
            total += handler.execute(level, frontier, nextFrontier);
            frontier = nextFrontier;
        }
        return total;
    }
}
