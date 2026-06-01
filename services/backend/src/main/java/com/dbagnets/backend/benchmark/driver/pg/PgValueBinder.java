package com.dbagnets.backend.benchmark.driver.pg;

import com.dbagnets.backend.benchmark.schema.LogicalAttribute;
import com.dbagnets.backend.benchmark.schema.LogicalDataType;
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
            stmt.setNull(index, jdbcType(attr.dataType()));
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
            case VECTOR -> stmt.setObject(index, formatVector((float[]) value), Types.OTHER);
        }
    }

    private static PGobject asJsonbObject(String json) throws SQLException {
        PGobject obj = new PGobject();
        obj.setType("jsonb");
        obj.setValue(json);
        return obj;
    }

    private static int jdbcType(LogicalDataType type) {
        return switch (type) {
            case UUID -> Types.OTHER;
            case STRING, TEXT, ENUM -> Types.VARCHAR;
            case JSON -> Types.OTHER;
            case INTEGER -> Types.INTEGER;
            case BIGINT -> Types.BIGINT;
            case FLOAT -> Types.FLOAT;
            case DOUBLE -> Types.DOUBLE;
            case DECIMAL -> Types.DECIMAL;
            case BOOLEAN -> Types.BOOLEAN;
            case DATE -> Types.DATE;
            case TIMESTAMP -> Types.TIMESTAMP;
            case VECTOR -> Types.OTHER;
        };
    }

    private static String formatVector(float[] vec) {
        StringBuilder sb = new StringBuilder(vec.length * 6 + 2);
        sb.append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
