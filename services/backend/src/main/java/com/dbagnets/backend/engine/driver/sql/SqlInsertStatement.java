package com.dbagnets.backend.engine.driver.sql;

import java.util.List;

import com.dbagnets.backend.engine.schema.LogicalAttribute;

public interface SqlInsertStatement {

    List<LogicalAttribute> orderedColumns();

    String singleRowSql();

    String multiRowSql(int rows);
}
