package com.dbagnets.backend.engine.driver.support;

import com.dbagnets.backend.engine.driver.api.InsertContext;

public final class BatchSizes {

    private BatchSizes() {}

    public static int effective(InsertContext ctx, int bulkDefault) {
        return switch (ctx.mode()) {
            case SINGLE -> 1;
            case BATCH -> Math.max(1, ctx.batchSize());
            case BULK -> Math.max(1, ctx.batchSize() > 0 ? ctx.batchSize() : bulkDefault);
        };
    }

    public static int effectiveCapped(InsertContext ctx, int bulkDefault, int hardCap) {
        int base =
                switch (ctx.mode()) {
                    case SINGLE -> 1;
                    case BATCH -> Math.max(1, ctx.batchSize());
                    case BULK -> Math.max(1, ctx.batchSize() > 0 ? ctx.batchSize() : bulkDefault);
                };
        return Math.min(hardCap, base);
    }
}
