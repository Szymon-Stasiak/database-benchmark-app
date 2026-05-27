package com.dbagnets.backend.insert.strategy;

import com.dbagnets.backend.docker.DockerService;

/**
 * SQLite usually runs as a library inside the consumer process — there is no
 * container backing it in this setup. Until a dedicated launcher exists, this
 * strategy short-circuits with an explanation.
 */
public class SqliteInsertStrategy implements DatabaseInsertStrategy {

    @Override
    public InsertOutcome insert(DockerService docker, InsertContext context) {
        return InsertOutcome.failure(
            "SQLite is embedded — no container to exec into. Insert benchmarking is disabled for SQLite in this version.",
            0
        );
    }
}
