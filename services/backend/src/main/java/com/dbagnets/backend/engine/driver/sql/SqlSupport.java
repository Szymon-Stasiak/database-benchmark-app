package com.dbagnets.backend.engine.driver.sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class SqlSupport {

    private SqlSupport() {}

    public static void safeRollback(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
        }
    }

    public static long executeSelectCount(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            long n = 0L;
            while (rs.next()) n++;
            return n;
        }
    }
}
