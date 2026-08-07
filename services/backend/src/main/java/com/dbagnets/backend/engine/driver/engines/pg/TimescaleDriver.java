package com.dbagnets.backend.engine.driver.engines.pg;

import org.springframework.stereotype.Component;

import com.dbagnets.backend.domain.DatabaseEngine;

@Component
public class TimescaleDriver extends PgDriver {

    public TimescaleDriver(PgDataSourceCache dataSources) {
        super(dataSources);
    }

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.TIMESCALEDB;
    }
}
