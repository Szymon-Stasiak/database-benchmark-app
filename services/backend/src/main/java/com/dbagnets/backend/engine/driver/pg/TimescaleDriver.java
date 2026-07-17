package com.dbagnets.backend.engine.driver.pg;

import com.dbagnets.backend.domain.DatabaseEngine;
import org.springframework.stereotype.Component;

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
