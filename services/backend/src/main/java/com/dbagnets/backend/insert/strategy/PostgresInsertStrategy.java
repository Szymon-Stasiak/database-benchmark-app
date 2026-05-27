package com.dbagnets.backend.insert.strategy;

public class PostgresInsertStrategy extends SqlInsertStrategy {

    @Override
    protected String[] clientCommand(InsertContext ctx) {
        return new String[]{"psql", "-U", "postgres", "-d", "benchmark", "-v", "ON_ERROR_STOP=1", "-q"};
    }
}
