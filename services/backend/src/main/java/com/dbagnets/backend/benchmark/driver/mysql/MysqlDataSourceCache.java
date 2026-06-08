package com.dbagnets.backend.benchmark.driver.mysql;

import com.dbagnets.backend.benchmark.driver.ConnectionCache;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MysqlDataSourceCache implements ConnectionCache {

    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    public DataSource get(MysqlConnectionInfo info) {
        return pools.computeIfAbsent(info.databaseId(), id -> create(info));
    }

    @Override
    public void evict(String databaseId) {
        HikariDataSource ds = pools.remove(databaseId);
        if (ds != null) {
            ds.close();
        }
    }

    @PreDestroy
    public void shutdown() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }

    private HikariDataSource create(MysqlConnectionInfo info) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(info.jdbcUrl());
        cfg.setUsername(info.user());
        cfg.setPassword(info.password());
        cfg.setMaximumPoolSize(info.poolSize());
        cfg.setConnectionTimeout(10_000L);
        cfg.setPoolName("mysql-" + info.databaseId());
        return new HikariDataSource(cfg);
    }
}
