package com.dbagnets.backend.engine.driver.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.dbagnets.backend.engine.cascade.CascadeNode;
import com.dbagnets.backend.engine.cascade.CascadePlan;
import com.dbagnets.backend.engine.datagen.GeneratedRow;
import com.dbagnets.backend.engine.driver.api.BatchProgress;
import com.dbagnets.backend.engine.driver.api.EntityOutcome;
import com.dbagnets.backend.engine.driver.api.InsertContext;
import com.dbagnets.backend.engine.driver.api.InsertMode;
import com.dbagnets.backend.engine.timing.RecordedId;

class InsertOuterLoopTest {

    @Test
    void iteratesAllNodesAndAggregatesOutcomes() throws Exception {
        InsertContext ctx =
                ctx(
                        Map.of(
                                "Users", List.of(row("Users", "u1"), row("Users", "u2")),
                                "Orders", List.of(row("Orders", "o1"))),
                        List.of(node("Users"), node("Orders")));

        var outcome =
                InsertOuterLoop.run(
                        ctx,
                        (node, rows) -> {
                            EntityOutcome eo = new EntityOutcome();
                            eo.dbTimeNs = 10;
                            eo.rowsAffected = rows.size();
                            eo.recordedIds = List.of(new RecordedId(node.entityName(), "l", "p"));
                            return eo;
                        });

        assertThat(outcome.dbTimeNs()).isEqualTo(20);
        assertThat(outcome.rowsAffected()).isEqualTo(3);
        assertThat(outcome.recordedIds()).hasSize(2);
    }

    @Test
    void skipsEmptyOrMissingRows() throws Exception {
        InsertContext ctx = ctx(Map.of("Users", List.of()), List.of(node("Users"), node("Orders")));

        Set<String> visited = new HashSet<>();
        var outcome =
                InsertOuterLoop.run(
                        ctx,
                        (node, rows) -> {
                            visited.add(node.entityName());
                            return new EntityOutcome();
                        });

        assertThat(visited).isEmpty();
        assertThat(outcome.rowsAffected()).isZero();
    }

    @Test
    void nullOutcomeIsSkipped() throws Exception {
        InsertContext ctx =
                ctx(Map.of("Users", List.of(row("Users", "u1"))), List.of(node("Users")));

        var outcome = InsertOuterLoop.run(ctx, (node, rows) -> null);

        assertThat(outcome.rowsAffected()).isZero();
    }

    @Test
    void propagatesExceptionFromHandler() {
        InsertContext ctx =
                ctx(Map.of("Users", List.of(row("Users", "u1"))), List.of(node("Users")));

        assertThatThrownBy(
                        () ->
                                InsertOuterLoop.run(
                                        ctx,
                                        (node, rows) -> {
                                            throw new IllegalStateException("nope");
                                        }))
                .isInstanceOf(IllegalStateException.class);
    }

    private CascadeNode node(String name) {
        return new CascadeNode(name, 0, List.of());
    }

    private GeneratedRow row(String entity, String id) {
        LinkedHashMap<String, Object> v = new LinkedHashMap<>();
        v.put("id", id);
        return new GeneratedRow(entity, id, v);
    }

    private InsertContext ctx(Map<String, List<GeneratedRow>> rows, List<CascadeNode> nodes) {
        CascadePlan plan = new CascadePlan(nodes);
        BatchProgress noop = (n, i, c, d, t) -> {};
        return new InsertContext(
                "bench",
                "db",
                "pg",
                "16",
                "localhost",
                5432,
                null,
                null,
                plan,
                rows,
                InsertMode.BATCH,
                100,
                noop);
    }
}
