package com.dbagnets.backend.engine.driver.support;

import java.util.List;

import com.dbagnets.backend.engine.cascade.CascadeNode;
import com.dbagnets.backend.engine.datagen.GeneratedRow;
import com.dbagnets.backend.engine.driver.api.EntityOutcome;
import com.dbagnets.backend.engine.driver.api.InsertContext;
import com.dbagnets.backend.engine.timing.TimedOperation;

public final class InsertOuterLoop {

    private InsertOuterLoop() {}

    @FunctionalInterface
    public interface EntityHandler {
        EntityOutcome handle(CascadeNode node, List<GeneratedRow> rows) throws Exception;
    }

    public static TimedOperation run(InsertContext ctx, EntityHandler handler) throws Exception {
        InsertAccumulator acc = new InsertAccumulator();
        long wireStart = System.nanoTime();
        for (CascadeNode node : ctx.plan().nodesInInsertOrder()) {
            List<GeneratedRow> rows = ctx.rowsByEntity().get(node.entityName());
            if (rows == null || rows.isEmpty()) continue;
            EntityOutcome outcome = handler.handle(node, rows);
            if (outcome != null) acc.accept(outcome);
            ctx.progress().onEntityFinished(node.entityName());
        }
        return acc.finish(System.nanoTime() - wireStart);
    }
}
