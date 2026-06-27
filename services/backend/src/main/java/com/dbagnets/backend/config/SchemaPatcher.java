package com.dbagnets.backend.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaPatcher {

    private final DataSource dataSource;

    @PostConstruct
    public void patch() throws Exception {
        boolean patched = false;
        try (Connection conn = dataSource.getConnection()) {
            patched |= patchOperationTypeCheck(conn);
        }
        if (patched && dataSource instanceof HikariDataSource hikari) {
            hikari.getHikariPoolMXBean().softEvictConnections();
            log.info("Evicted Hikari pool connections to apply patched schema");
        }
    }

    private boolean patchOperationTypeCheck(Connection conn) throws Exception {
        String currentSql = currentTableSql(conn, "benchmark_runs");
        if (currentSql == null) return false;
        String oldCheck = "check (operation_type in ('INSERT','READ','DELETE'))";
        String newCheck = "check (operation_type in ('INSERT','READ','DELETE','SCENARIO'))";
        if (!currentSql.contains(oldCheck)) {
            if (!currentSql.contains("'SCENARIO'")) {
                log.warn("benchmark_runs operation_type check constraint has unexpected form — skipping patch");
            }
            return false;
        }
        String patchedSql = currentSql.replace(oldCheck, newCheck);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA writable_schema = 1");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE sqlite_master SET sql = ? WHERE type = 'table' AND name = 'benchmark_runs'")) {
            ps.setString(1, patchedSql);
            ps.executeUpdate();
        }
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA writable_schema = 0");
            st.execute("PRAGMA schema_version = schema_version + 1");
        }
        log.info("Patched benchmark_runs.operation_type check constraint to allow SCENARIO");
        return true;
    }

    private String currentTableSql(Connection conn, String table) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        return null;
    }
}
