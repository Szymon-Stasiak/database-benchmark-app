package com.dbagnets.backend.engine.driver.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

class SqlSupportTest {

    @Test
    void safeRollbackCallsConnectionRollback() throws SQLException {
        Connection conn = mock(Connection.class);
        doNothing().when(conn).rollback();

        SqlSupport.safeRollback(conn);

        verify(conn, times(1)).rollback();
    }

    @Test
    void safeRollbackSwallowsSqlException() throws SQLException {
        Connection conn = mock(Connection.class);
        doThrow(new SQLException("boom")).when(conn).rollback();

        assertThatCode(() -> SqlSupport.safeRollback(conn)).doesNotThrowAnyException();
    }

    @Test
    void executeSelectCountCountsRows() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, true, false);

        long count = SqlSupport.executeSelectCount(ps);

        assertThat(count).isEqualTo(3L);
    }

    @Test
    void executeSelectCountZeroWhenNoRows() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        assertThat(SqlSupport.executeSelectCount(ps)).isZero();
    }
}
