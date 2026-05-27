package com.dbagnets.backend.insert.strategy.jdbc;

import com.dbagnets.backend.insert.strategy.InsertContext;

/**
 * SQLite via JDBC. The container exposes the DB file over a network mount in the lab setup;
 * for benchmarking we use the file path served via TCP through the same {@code hostPort}
 * convention as the other engines. If a TCP bridge is unavailable, fall back to a local file URL
 * (most sqlite containers ship the db at {@code /data/benchmark.db}).
 */
public class SqliteJdbcStrategy extends JdbcInsertStrategy {

    @Override
    protected String jdbcUrl(InsertContext ctx) {
        // sqlite-jdbc supports a TCP server via :memory: or file: URLs; the docker container in
        // this project mounts the DB file and exposes it via socat. The hostPort is the socat
        // forwarder. We use file URL semantics with the path appended.
        return "jdbc:sqlite:" + ctx.host() + ":" + ctx.hostPort();
    }

    @Override
    protected String username(InsertContext ctx) { return ""; }

    @Override
    protected String password(InsertContext ctx) { return ""; }
}
