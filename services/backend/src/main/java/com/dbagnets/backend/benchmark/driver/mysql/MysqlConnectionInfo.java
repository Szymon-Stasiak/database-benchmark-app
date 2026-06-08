package com.dbagnets.backend.benchmark.driver.mysql;

public record MysqlConnectionInfo(
        String databaseId,
        String jdbcUrl,
        String user,
        String password,
        int poolSize
) {
    public static MysqlConnectionInfo defaultLocal(String databaseId, String host, int port) {
        return new MysqlConnectionInfo(
                databaseId,
                "jdbc:mysql://" + host + ":" + port + "/benchmark?useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true",
                "root",
                "root",
                4);
    }
}
