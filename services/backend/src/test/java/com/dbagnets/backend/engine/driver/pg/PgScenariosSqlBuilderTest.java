package com.dbagnets.backend.engine.driver.pg;

import com.dbagnets.backend.engine.schema.LogicalSchema;
import com.dbagnets.backend.engine.schema.LogicalSchemaLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PgScenariosSqlBuilderTest {

    private LogicalSchema schema;

    @BeforeEach
    void setUp() {
        LogicalSchemaLoader loader = new LogicalSchemaLoader(new ObjectMapper());
        schema = loader.parse(threeLevelSchema());
    }

    @Test
    void aggregateGroupsByForeignKey() {
        String sql = PgScenarios.buildAggregateSql(schema, "Customer", "Order");
        assertThat(sql)
                .contains("FROM \"order\"")
                .contains("GROUP BY \"customer_id\"")
                .contains("ORDER BY \"customer_id\"")
                .contains("WHERE \"customer_id\" IS NOT NULL");
    }

    @Test
    void aggregateThrowsOnUnknownRelationship() {
        assertThatThrownBy(() -> PgScenarios.buildAggregateSql(schema, "Order", "Customer"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rangeBuildsBetweenClause() {
        String sql = PgScenarios.buildRangeSql(schema, "Order", "amount");
        assertThat(sql)
                .contains("FROM \"order\"")
                .contains("WHERE \"amount\" BETWEEN ? AND ?");
    }

    @Test
    void rangeRejectsNonNumericAttribute() {
        assertThatThrownBy(() -> PgScenarios.buildRangeSql(schema, "Customer", "name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not numeric");
    }

    @Test
    void traversalChainContainsCtePerLevel() {
        String sql = PgScenarios.buildTraversalSql(schema, "Customer", 3);
        assertThat(sql)
                .contains("walk_0")
                .contains("walk_1")
                .contains("walk_2")
                .contains("UNION ALL")
                .contains("ORDER BY reachable_id");
    }

    @Test
    void traversalChainStopsAtSchemaLeaf() {
        String sql = PgScenarios.buildTraversalSql(schema, "Customer", 5);
        assertThat(sql).contains("walk_2");
        assertThat(sql).doesNotContain("walk_4");
    }

    @Test
    void traversalResolvesChainLevels() {
        var chain = PgScenarios.resolveChain(schema, "Customer", 5);
        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).childEntity()).isEqualTo("Order");
        assertThat(chain.get(1).childEntity()).isEqualTo("OrderItem");
    }

    private String threeLevelSchema() {
        return """
            {
              "idea": "ecommerce",
              "depth": 3,
              "entities": [
                {
                  "name": "Customer",
                  "attributes": [
                    {"name": "customer_id", "data_type": "uuid",
                      "constraints": {"is_primary_key": true, "is_unique": true, "is_nullable": false, "is_indexed": false}},
                    {"name": "name", "data_type": "string",
                      "constraints": {"is_primary_key": false, "is_unique": false, "is_nullable": false, "is_indexed": false}}
                  ]
                },
                {
                  "name": "Order",
                  "attributes": [
                    {"name": "order_id", "data_type": "uuid",
                      "constraints": {"is_primary_key": true, "is_unique": true, "is_nullable": false, "is_indexed": false}},
                    {"name": "amount", "data_type": "double",
                      "constraints": {"is_primary_key": false, "is_unique": false, "is_nullable": false, "is_indexed": false}},
                    {"name": "customer_id", "data_type": "uuid",
                      "constraints": {"is_primary_key": false, "is_unique": false, "is_nullable": false, "is_indexed": false}}
                  ]
                },
                {
                  "name": "OrderItem",
                  "attributes": [
                    {"name": "item_id", "data_type": "uuid",
                      "constraints": {"is_primary_key": true, "is_unique": true, "is_nullable": false, "is_indexed": false}},
                    {"name": "order_id", "data_type": "uuid",
                      "constraints": {"is_primary_key": false, "is_unique": false, "is_nullable": false, "is_indexed": false}}
                  ]
                }
              ],
              "relationships": [
                {"name": "customer_orders", "source_entity": "Customer", "target_entity": "Order",
                  "cardinality": "ONE_TO_MANY", "fk_column_in_child": "customer_id"},
                {"name": "order_items", "source_entity": "Order", "target_entity": "OrderItem",
                  "cardinality": "ONE_TO_MANY", "fk_column_in_child": "order_id"}
              ]
            }
            """;
    }
}
