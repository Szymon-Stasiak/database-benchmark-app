package com.dbagnets.backend.engine.driver.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.dbagnets.backend.engine.driver.engines.pg.PgScenarios;

class FrontierBfsTest {

    @Test
    void descendReturnsZeroForEmptyChain() {
        long total =
                FrontierBfs.descend(
                        List.of(),
                        "root",
                        (level, frontier, next) -> {
                            throw new AssertionError("should not run");
                        });
        assertThat(total).isZero();
    }

    @Test
    void descendIteratesLevelsAndAccumulatesTotal() {
        List<PgScenarios.TraversalLevel> chain =
                List.of(
                        new PgScenarios.TraversalLevel(1, "Users", "Orders", "user_id"),
                        new PgScenarios.TraversalLevel(2, "Orders", "Items", "order_id"));

        long total =
                FrontierBfs.descend(
                        chain,
                        "u1",
                        (level, frontier, next) -> {
                            if (level.childEntity().equals("Orders")) {
                                next.add("o1");
                                next.add("o2");
                                return 2L;
                            }
                            next.add("i1");
                            return 1L;
                        });

        assertThat(total).isEqualTo(3L);
    }

    @Test
    void descendStopsWhenFrontierEmpty() {
        AtomicInteger calls = new AtomicInteger();
        List<PgScenarios.TraversalLevel> chain =
                List.of(
                        new PgScenarios.TraversalLevel(1, "Users", "Orders", "user_id"),
                        new PgScenarios.TraversalLevel(2, "Orders", "Items", "order_id"));

        long total =
                FrontierBfs.descend(
                        chain,
                        "u1",
                        (level, frontier, next) -> {
                            calls.incrementAndGet();
                            return 0L;
                        });

        assertThat(total).isZero();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void descendSqlPropagatesSqlException() {
        List<PgScenarios.TraversalLevel> chain =
                List.of(new PgScenarios.TraversalLevel(1, "Users", "Orders", "user_id"));

        assertThatThrownBy(
                        () ->
                                FrontierBfs.descendSql(
                                        chain,
                                        "u1",
                                        (level, frontier, next) -> {
                                            throw new SQLException("boom");
                                        }))
                .isInstanceOf(SQLException.class)
                .hasMessage("boom");
    }

    @Test
    void descendSqlWorksIdenticallyToDescendWhenNoException() throws SQLException {
        List<PgScenarios.TraversalLevel> chain =
                List.of(new PgScenarios.TraversalLevel(1, "Users", "Orders", "user_id"));

        long total =
                FrontierBfs.descendSql(
                        chain,
                        "u1",
                        (level, frontier, next) -> {
                            next.add("o1");
                            return 1L;
                        });

        assertThat(total).isEqualTo(1L);
    }
}
