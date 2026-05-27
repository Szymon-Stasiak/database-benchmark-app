package com.dbagnets.backend.insert.strategy.jdbc;

import com.dbagnets.backend.insert.strategy.InsertContext;

public class MysqlJdbcStrategy extends JdbcInsertStrategy {

    @Override
    protected String jdbcUrl(InsertContext ctx) {
        return "jdbc:mysql://" + ctx.host() + ":" + ctx.hostPort()
            + "/benchmark?useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true";
    }

    @Override
    protected String username(InsertContext ctx) {
        return "root";
    }

    /** Must match {@code MYSQL_ROOT_PASSWORD} set in {@code BenchmarkService.getDefaultEnvironment}. */
    @Override
    protected String password(InsertContext ctx) {
        return "root";
    }

    @Override
    protected String quoteIdent(String name) {
        return "`" + name.replace("`", "``") + "`";
    }
}
