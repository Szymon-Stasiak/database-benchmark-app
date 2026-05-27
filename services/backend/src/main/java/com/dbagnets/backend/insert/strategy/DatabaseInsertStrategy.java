package com.dbagnets.backend.insert.strategy;

import com.dbagnets.backend.docker.DockerService;

/**
 * Pluggable insert path for one database technology.
 *
 * <p>Two execution flavours coexist while the legacy docker-exec strategies are being migrated to
 * native clients:
 *
 * <ul>
 *   <li>Legacy docker-exec strategies override {@link #insert(DockerService, InsertContext)} —
 *       the orchestrator passes them a {@link DockerService} and they shell out via
 *       {@code docker exec}. Easy but bakes in process/IO overhead.</li>
 *   <li>Native-client strategies (JDBC, Bolt, native Mongo, Jedis, etc.) override
 *       {@link #insert(DockerService, InsertContext, BatchProgressCallback)} and ignore the
 *       {@code DockerService} parameter. They open real connections via
 *       {@link InsertContext#host()} / {@link InsertContext#hostPort()}, time only the
 *       in-driver insert call, and emit per-batch progress events.</li>
 * </ul>
 *
 * <p>The orchestrator always calls the three-argument variant; the default impl falls back to the
 * legacy two-argument method so unmigrated strategies keep working.
 */
public interface DatabaseInsertStrategy {

    /** Legacy entry point — docker-exec strategies override this. Native-client strategies leave
     *  it at the default (throws), since the orchestrator goes through the three-arg variant. */
    default InsertOutcome insert(DockerService docker, InsertContext context) {
        throw new UnsupportedOperationException(
            "Strategy " + getClass().getSimpleName() + " requires the (docker, ctx, progress) entry point");
    }

    /** Preferred entry point: native strategies override this and ignore {@code docker}. The
     *  default delegates to the legacy method so docker-exec strategies still work unchanged. */
    default InsertOutcome insert(DockerService docker, InsertContext context, BatchProgressCallback progress) {
        return insert(docker, context);
    }
}
