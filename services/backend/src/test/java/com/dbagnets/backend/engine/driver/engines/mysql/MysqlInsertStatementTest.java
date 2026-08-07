package com.dbagnets.backend.engine.driver.engines.mysql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.dbagnets.backend.engine.schema.AttributeConstraints;
import com.dbagnets.backend.engine.schema.LogicalAttribute;
import com.dbagnets.backend.engine.schema.LogicalDataType;
import com.dbagnets.backend.engine.schema.LogicalEntity;

class MysqlInsertStatementTest {

    @Test
    void singleRowSqlUsesBackticksAndInsertIgnore() {
        LogicalEntity entity = entity("Users", "id", "name");

        MysqlInsertStatement stmt = MysqlInsertStatement.of(entity);

        assertThat(stmt.singleRowSql())
                .isEqualTo("INSERT IGNORE INTO `users` (`id`, `name`) VALUES (?, ?)");
    }

    @Test
    void multiRowSqlUsesBackticks() {
        LogicalEntity entity = entity("Users", "id", "name");

        MysqlInsertStatement stmt = MysqlInsertStatement.of(entity);

        assertThat(stmt.multiRowSql(2))
                .isEqualTo("INSERT IGNORE INTO `users` (`id`, `name`) VALUES (?, ?), (?, ?)");
    }

    @Test
    void escapesEmbeddedBackticks() {
        LogicalEntity entity =
                new LogicalEntity(
                        "back`tick",
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

        MysqlInsertStatement stmt = MysqlInsertStatement.of(entity);

        assertThat(stmt.singleRowSql()).contains("`back``tick`");
    }

    @Test
    void orderedColumnsMatchesEntityOrder() {
        LogicalEntity entity = entity("Users", "a", "b", "c");

        MysqlInsertStatement stmt = MysqlInsertStatement.of(entity);

        assertThat(stmt.orderedColumns())
                .extracting(LogicalAttribute::name)
                .containsExactly("a", "b", "c");
    }

    private LogicalEntity entity(String name, String... cols) {
        List<LogicalAttribute> attrs =
                Arrays.stream(cols)
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
