package com.dbagnets.backend.engine.driver.pg;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers
class PgDataSourceCacheIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("benchmark")
            .withUsername("postgres")
            .withPassword("benchmark");

    PgDataSourceCache cache;

    @BeforeEach
    void setUp() {
        cache = new PgDataSourceCache();
    }

    @AfterEach
    void tearDown() {
        cache.shutdown();
    }

    @Test
    void connectsToRealPostgresAndExecutesQuery() throws Exception {
        PgConnectionInfo info = new PgConnectionInfo(
                "test-db",
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                2);

        DataSource ds = cache.get(info);

        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE products (id INT PRIMARY KEY, name TEXT NOT NULL, price NUMERIC(10,2))");
            stmt.executeUpdate("INSERT INTO products (id, name, price) VALUES (1, 'Widget', 9.99), (2, 'Gadget', 19.50)");

            try (ResultSet rs = stmt.executeQuery("SELECT id, name, price FROM products ORDER BY id")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("id")).isEqualTo(1);
                assertThat(rs.getString("name")).isEqualTo("Widget");
                assertThat(rs.getBigDecimal("price").doubleValue()).isEqualTo(9.99);

                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("id")).isEqualTo(2);
                assertThat(rs.getString("name")).isEqualTo("Gadget");

                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    void reusesPooledConnectionForSameDatabaseId() {
        PgConnectionInfo info = new PgConnectionInfo(
                "shared-db",
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                2);

        DataSource first = cache.get(info);
        DataSource second = cache.get(info);

        assertThat(first).isSameAs(second);
    }

    @Test
    void evictClosesPoolAndForcesRebuild() {
        PgConnectionInfo info = new PgConnectionInfo(
                "evictable-db",
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                2);

        DataSource first = cache.get(info);
        cache.evict("evictable-db");
        DataSource second = cache.get(info);

        assertThat(first).isNotSameAs(second);
    }

    @Test
    void transactionRollbackDiscardsWrites() throws Exception {
        PgConnectionInfo info = new PgConnectionInfo(
                "tx-db",
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                2);
        DataSource ds = cache.get(info);

        try (Connection setup = ds.getConnection(); Statement stmt = setup.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS accounts (id INT PRIMARY KEY, balance INT NOT NULL)");
            stmt.executeUpdate("TRUNCATE accounts");
        }

        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("INSERT INTO accounts (id, balance) VALUES (1, 100)");
            }
            conn.rollback();
        }

        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM accounts")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isZero();
        }
    }
}
