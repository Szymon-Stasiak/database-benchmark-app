package com.dbagnets.backend.engine.driver;

import com.dbagnets.backend.engine.driver.pg.PgScenarios;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class FrontierBfs {

    private FrontierBfs() {}

    @FunctionalInterface
    public interface LevelHandler {
        long execute(PgScenarios.TraversalLevel level, List<Object> frontier, List<Object> nextFrontier);
    }

    @FunctionalInterface
    public interface SqlLevelHandler {
        long execute(PgScenarios.TraversalLevel level, List<Object> frontier, List<Object> nextFrontier) throws SQLException;
    }

    public static long descend(List<PgScenarios.TraversalLevel> chain, Object rootId, LevelHandler handler) {
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

    public static long descendSql(List<PgScenarios.TraversalLevel> chain, Object rootId, SqlLevelHandler handler) throws SQLException {
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
