package com.dbagnets.backend.engine.driver.pg;

public record PgConnectionInfo(String databaseId, String jdbcUrl, String user, String password, int poolSize) {
    public static PgConnectionInfo defaultLocal(String databaseId, String host, int port) {
        return new PgConnectionInfo(databaseId, "jdbc:postgresql://" + host + ":" + port + "/benchmark", "postgres", "benchmark", 4);
    }

    public static PgConnectionInfo forQuestdb(String databaseId, String host, int port) {
        return new PgConnectionInfo(databaseId, "jdbc:postgresql://" + host + ":" + port + "/qdb?preferQueryMode=simple", "admin", "quest", 4);
    }
}