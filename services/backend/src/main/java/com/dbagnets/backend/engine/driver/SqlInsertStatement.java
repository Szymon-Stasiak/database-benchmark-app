package com.dbagnets.backend.engine.driver;

import com.dbagnets.backend.engine.schema.LogicalAttribute;

import java.util.List;

public interface SqlInsertStatement {

    String tableName();

    List<LogicalAttribute> orderedColumns();

    String singleRowSql();

    String multiRowSql(int rows);
}
