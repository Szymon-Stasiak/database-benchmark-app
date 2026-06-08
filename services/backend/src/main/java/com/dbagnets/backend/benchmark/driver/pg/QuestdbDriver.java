package com.dbagnets.backend.benchmark.driver.pg;

import com.dbagnets.backend.benchmark.schema.LogicalEntity;
import com.dbagnets.backend.entity.DatabaseEngine;
import org.springframework.stereotype.Component;

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
