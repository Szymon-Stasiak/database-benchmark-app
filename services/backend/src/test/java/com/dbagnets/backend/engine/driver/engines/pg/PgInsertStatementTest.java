package com.dbagnets.backend.engine.driver.engines.pg;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dbagnets.backend.engine.schema.AttributeConstraints;
import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalDataType;
import com.dbagnets.backend.engine.schema.LogicalEntity;

class PgInsertStatementTest {

    @Test
    void singleRowSqlIncludesOnConflictByDefault() {
        LogicalEntity entity = entity("Users", "id", "name");

        PgInsertStatement stmt = PgInsertStatement.of(entity);

        assertThat(stmt.singleRowSql())
                .isEqualTo(
                        "INSERT INTO \"users\" (\"id\", \"name\") VALUES (?, ?) ON CONFLICT DO NOTHING");
        assertThat(stmt.withConflictClause()).isTrue();
    }

    @Test
    void singleRowSqlWithoutConflictWhenDisabled() {
        LogicalEntity entity = entity("Users", "id");

        PgInsertStatement stmt = PgInsertStatement.of(entity, false);

        assertThat(stmt.singleRowSql()).isEqualTo("INSERT INTO \"users\" (\"id\") VALUES (?)");
        assertThat(stmt.withConflictClause()).isFalse();
    }

    @Test
    void multiRowSqlBuildsCorrectPlaceholders() {
        LogicalEntity entity = entity("Users", "id", "name");

        PgInsertStatement stmt = PgInsertStatement.of(entity);
        String multi = stmt.multiRowSql(3);

        assertThat(multi)
                .isEqualTo(
                        "INSERT INTO \"users\" (\"id\", \"name\") VALUES (?, ?), (?, ?), (?, ?) ON CONFLICT DO NOTHING");
    }

    @Test
    void multiRowSqlSingleRow() {
        LogicalEntity entity = entity("Users", "id");

        PgInsertStatement stmt = PgInsertStatement.of(entity);

        assertThat(stmt.multiRowSql(1))
                .isEqualTo("INSERT INTO \"users\" (\"id\") VALUES (?) ON CONFLICT DO NOTHING");
    }

    @Test
    void quotesEscapeEmbeddedDoubleQuotes() {
        LogicalEntity entity =
                new LogicalEntity(
                        "with\"quote",
                        "",
                        List.of(
                                new LogicalAttribute(
                                        "id",
                                        LogicalDataType.STRING,
                                        AttributeConstraints.NONE,
                                        "",
                                        null,
                                        List.of(),
                                        null,
                                        null)));

        PgInsertStatement stmt = PgInsertStatement.of(entity);

        assertThat(stmt.singleRowSql()).contains("\"with\"\"quote\"");
    }

    @Test
    void orderedColumnsMatchesEntityOrder() {
        LogicalEntity entity = entity("Users", "id", "name", "email");

        PgInsertStatement stmt = PgInsertStatement.of(entity);

        assertThat(stmt.orderedColumns())
                .extracting(LogicalAttribute::name)
                .containsExactly("id", "name", "email");
    }

    private LogicalEntity entity(String name, String... cols) {
        List<LogicalAttribute> attrs =
                java.util.Arrays.stream(cols)
                        .map(
                                c ->
                                        new LogicalAttribute(
                                                c,
                                                LogicalDataType.STRING,
                                                AttributeConstraints.NONE,
                                                "",
                                                null,
                                                List.of(),
                                                null,
                                                null))
                        .toList();
        return new LogicalEntity(name, "", attrs);
    }
}
