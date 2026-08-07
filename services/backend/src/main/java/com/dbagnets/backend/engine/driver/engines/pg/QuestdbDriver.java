package com.dbagnets.backend.engine.driver.engines.pg;

import org.springframework.stereotype.Component;

import com.dbagnets.backend.domain.DatabaseEngine;
import com.dbagnets.backend.engine.schema.LogicalEntity;

@Component
public class QuestdbDriver extends PgDriver {

    public QuestdbDriver(PgDataSourceCache dataSources) {
        super(dataSources);
    }

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.QUESTDB;
    }

    @Override
    protected PgConnectionInfo connectionInfo(String databaseId, String host, int port) {
        return PgConnectionInfo.forQuestdb(databaseId, host, port);
    }

    @Override
    protected PgInsertStatement buildInsertStatement(LogicalEntity entity) {
        return PgInsertStatement.of(entity, false);
    }
}
