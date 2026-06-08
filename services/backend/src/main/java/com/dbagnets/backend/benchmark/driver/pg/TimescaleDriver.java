package com.dbagnets.backend.benchmark.driver.pg;

import com.dbagnets.backend.entity.DatabaseEngine;
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
