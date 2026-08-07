package com.dbagnets.backend.engine.driver.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;

import org.junit.jupiter.api.Test;

import com.dbagnets.backend.engine.schema.LogicalDataType;

class SqlTypesTest {

    @Test
    void pgUuidMapsToOther() {
        assertThat(SqlTypes.jdbcType(LogicalDataType.UUID, Types.OTHER)).isEqualTo(Types.OTHER);
    }

    @Test
    void mysqlUuidMapsToVarchar() {
        assertThat(SqlTypes.jdbcType(LogicalDataType.UUID, Types.VARCHAR)).isEqualTo(Types.VARCHAR);
    }

    @Test
    void stringAlwaysMapsToVarcharRegardlessOfOverride() {
        assertThat(SqlTypes.jdbcType(LogicalDataType.STRING, Types.OTHER)).isEqualTo(Types.VARCHAR);
        assertThat(SqlTypes.jdbcType(LogicalDataType.TEXT, Types.OTHER)).isEqualTo(Types.VARCHAR);
        assertThat(SqlTypes.jdbcType(LogicalDataType.ENUM, Types.OTHER)).isEqualTo(Types.VARCHAR);
    }

    @Test
    void integerMapsToInteger() {
        assertThat(SqlTypes.jdbcType(LogicalDataType.INTEGER, Types.OTHER))
                .isEqualTo(Types.INTEGER);
    }

    @Test
    void bigintMapsToBigint() {
        assertThat(SqlTypes.jdbcType(LogicalDataType.BIGINT, Types.OTHER)).isEqualTo(Types.BIGINT);
    }

    @Test
    void floatingPointTypesMapToNumericTypes() {
        assertThat(SqlTypes.jdbcType(LogicalDataType.FLOAT, Types.OTHER)).isEqualTo(Types.FLOAT);
        assertThat(SqlTypes.jdbcType(LogicalDataType.DOUBLE, Types.OTHER)).isEqualTo(Types.DOUBLE);
        assertThat(SqlTypes.jdbcType(LogicalDataType.DECIMAL, Types.OTHER))
                .isEqualTo(Types.DECIMAL);
    }

    @Test
    void booleanMapsToBoolean() {
        assertThat(SqlTypes.jdbcType(LogicalDataType.BOOLEAN, Types.OTHER))
                .isEqualTo(Types.BOOLEAN);
    }

    @Test
    void dateAndTimestampMapNatively() {
        assertThat(SqlTypes.jdbcType(LogicalDataType.DATE, Types.VARCHAR)).isEqualTo(Types.DATE);
        assertThat(SqlTypes.jdbcType(LogicalDataType.TIMESTAMP, Types.VARCHAR))
                .isEqualTo(Types.TIMESTAMP);
    }

    @Test
    void vectorUsesOverride() {
        assertThat(SqlTypes.jdbcType(LogicalDataType.VECTOR, Types.OTHER)).isEqualTo(Types.OTHER);
        assertThat(SqlTypes.jdbcType(LogicalDataType.VECTOR, Types.VARCHAR))
                .isEqualTo(Types.VARCHAR);
    }

    @Test
    void jsonUsesOverride() {
        assertThat(SqlTypes.jdbcType(LogicalDataType.JSON, Types.OTHER)).isEqualTo(Types.OTHER);
        assertThat(SqlTypes.jdbcType(LogicalDataType.JSON, Types.VARCHAR)).isEqualTo(Types.VARCHAR);
    }

    @Test
    void formatVectorProducesBracketedCommaSeparated() {
        float[] vec = {1.0f, 2.5f, 3.5f};
        assertThat(SqlTypes.formatVector(vec)).isEqualTo("[1.0,2.5,3.5]");
    }

    @Test
    void formatVectorEmptyReturnsEmptyBrackets() {
        assertThat(SqlTypes.formatVector(new float[0])).isEqualTo("[]");
    }

    @Test
    void formatVectorSingleElement() {
        assertThat(SqlTypes.formatVector(new float[] {42.5f})).isEqualTo("[42.5]");
    }
}
