package com.dbagnets.backend.engine.driver.engines.mysql;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;

import com.dbagnets.backend.engine.driver.sql.SqlTypes;
import com.dbagnets.backend.engine.schema.LogicalAttribute;

public final class MysqlValueBinder {

    private MysqlValueBinder() {}

    public static void bind(PreparedStatement stmt, int index, LogicalAttribute attr, Object value)
            throws SQLException {
        if (value == null) {
            stmt.setNull(index, SqlTypes.jdbcType(attr.dataType(), Types.VARCHAR));
            return;
        }
        switch (attr.dataType()) {
            case UUID, STRING, TEXT, ENUM, JSON -> stmt.setString(index, value.toString());
            case INTEGER -> stmt.setInt(index, ((Number) value).intValue());
            case BIGINT -> stmt.setLong(index, ((Number) value).longValue());
            case FLOAT -> stmt.setFloat(index, ((Number) value).floatValue());
            case DOUBLE -> stmt.setDouble(index, ((Number) value).doubleValue());
            case DECIMAL ->
                    stmt.setBigDecimal(
                            index,
                            value instanceof BigDecimal bd ? bd : new BigDecimal(value.toString()));
            case BOOLEAN -> stmt.setBoolean(index, (Boolean) value);
            case DATE ->
                    stmt.setDate(
                            index,
                            value instanceof LocalDate ld
                                    ? Date.valueOf(ld)
                                    : Date.valueOf(value.toString()));
            case TIMESTAMP ->
                    stmt.setTimestamp(
                            index,
                            value instanceof Instant ins
                                    ? Timestamp.from(ins)
                                    : Timestamp.valueOf(value.toString()));
            case VECTOR -> stmt.setString(index, SqlTypes.formatVector((float[]) value));
        }
    }
}
