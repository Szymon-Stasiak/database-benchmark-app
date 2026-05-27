package com.dbagnets.backend.insert.strategy.jdbc;

/**
 * TimescaleDB is wire-compatible with PostgreSQL — it loads as a PG extension. The JDBC URL,
 * driver, and identifier conventions are identical; only the docker image differs.
 */
public class TimescaleJdbcStrategy extends PostgresJdbcStrategy {
}
