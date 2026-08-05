package com.dbagnets.backend.engine.driver.pg;

import com.dbagnets.backend.engine.driver.SqlTypes;
import com.dbagnets.backend.engine.schema.LogicalAttribute;
import org.postgresql.util.PGobject;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class PgValueBinder {

    private PgValueBinder() {
    }

    public static void bind(PreparedStatement stmt, int index, LogicalAttribute attr, Object value) throws SQLException {
        if (value == null) {
            stmt.setNull(index, SqlTypes.jdbcType(attr.dataType(), Types.OTHER));
            return;
        }
        switch (attr.dataType()) {
            case UUID -> stmt.setObject(index, UUID.fromString(value.toString()));
            case STRING, TEXT, ENUM -> stmt.setString(index, value.toString());
            case JSON -> stmt.setObject(index, asJsonbObject(value.toString()));
            case INTEGER -> stmt.setInt(index, ((Number) value).intValue());
            case BIGINT -> stmt.setLong(index, ((Number) value).longValue());
            case FLOAT -> stmt.setFloat(index, ((Number) value).floatValue());
            case DOUBLE -> stmt.setDouble(index, ((Number) value).doubleValue());
            case DECIMAL -> stmt.setBigDecimal(index, value instanceof BigDecimal bd ? bd : new BigDecimal(value.toString()));
            case BOOLEAN -> stmt.setBoolean(index, (Boolean) value);
            case DATE -> stmt.setDate(index, value instanceof LocalDate ld ? Date.valueOf(ld) : Date.valueOf(value.toString()));
            case TIMESTAMP -> stmt.setTimestamp(index, value instanceof Instant ins ? Timestamp.from(ins) : Timestamp.valueOf(value.toString()));
            case VECTOR -> stmt.setObject(index, SqlTypes.formatVector((float[]) value), Types.OTHER);
        }
    }

    private static PGobject asJsonbObject(String json) throws SQLException {
        PGobject obj = new PGobject();
        obj.setType("jsonb");
        obj.setValue(json);
        return obj;
    }
}
