package com.dbagnets.backend.engine.driver.sql;

import java.sql.Types;

import com.dbagnets.backend.engine.schema.LogicalDataType;

public final class SqlTypes {

    private SqlTypes() {}

    public static int jdbcType(LogicalDataType type, int uuidJsonVectorType) {
        return switch (type) {
            case UUID, JSON, VECTOR -> uuidJsonVectorType;
            case STRING, TEXT, ENUM -> Types.VARCHAR;
            case INTEGER -> Types.INTEGER;
            case BIGINT -> Types.BIGINT;
            case FLOAT -> Types.FLOAT;
            case DOUBLE -> Types.DOUBLE;
            case DECIMAL -> Types.DECIMAL;
            case BOOLEAN -> Types.BOOLEAN;
            case DATE -> Types.DATE;
            case TIMESTAMP -> Types.TIMESTAMP;
        };
    }

    public static String formatVector(float[] vec) {
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
