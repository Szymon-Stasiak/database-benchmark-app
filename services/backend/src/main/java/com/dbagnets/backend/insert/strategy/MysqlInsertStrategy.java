package com.dbagnets.backend.insert.strategy;

public class MysqlInsertStrategy extends SqlInsertStrategy {

    @Override
    protected String[] clientCommand(InsertContext ctx) {
        return new String[]{"mysql", "-u", "root", "--password=root", "--batch", "benchmark"};
    }

    @Override
    protected String quoteIdent(String name) {
        return "`" + name.replace("`", "``") + "`";
    }

    @Override
    protected String beginTransaction() {
        return "START TRANSACTION;";
    }

    @Override
    protected String detectError(String output) {
        if (output == null) return null;
        String lower = output.toLowerCase();
        if (lower.contains("error ") || lower.contains("error:")) {
            return output.lines().filter(l -> l.toLowerCase().contains("error")).findFirst().orElse(output);
        }
        return null;
    }
}
