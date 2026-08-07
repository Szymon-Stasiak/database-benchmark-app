package com.dbagnets.backend.engine.driver.engines.mysql;

import javax.sql.DataSource;

import org.springframework.stereotype.Component;

import com.dbagnets.backend.domain.DatabaseEngine;
import com.dbagnets.backend.engine.driver.engines.pg.SqlCascadeDeleter;
import com.dbagnets.backend.engine.driver.sql.AbstractSqlDriver;
import com.dbagnets.backend.engine.driver.sql.SqlInsertStatement;
import com.dbagnets.backend.engine.schema.LogicalEntity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class MysqlDriver extends AbstractSqlDriver {

    private static final SqlCascadeDeleter.Dialect DIALECT =
            new SqlCascadeDeleter.Dialect(
                    ident -> "`" + ident.replace("`", "``") + "`", MysqlValueBinder::bind);

    private final MysqlDataSourceCache dataSources;

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.MYSQL;
    }

    @Override
    protected DataSource dataSource(String databaseId, String host, int port) {
        return dataSources.get(MysqlConnectionInfo.defaultLocal(databaseId, host, port));
    }

    @Override
    protected SqlCascadeDeleter.Dialect dialect() {
        return DIALECT;
    }

    @Override
    protected SqlCascadeDeleter.Binder binder() {
        return MysqlValueBinder::bind;
    }

    @Override
    protected SqlInsertStatement buildInsertStatement(LogicalEntity entity) {
        return MysqlInsertStatement.of(entity);
    }

    @Override
    protected String orphanConstraintSql() {
        return "SET FOREIGN_KEY_CHECKS=0";
    }

    @Override
    protected String engineLogName() {
        return "MySQL";
    }
}
