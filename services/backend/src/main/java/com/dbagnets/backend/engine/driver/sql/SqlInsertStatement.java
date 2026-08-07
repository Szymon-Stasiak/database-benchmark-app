package com.dbagnets.backend.engine.driver.sql;

import com.dbagnets.backend.engine.schema.LogicalAttribute;

import java.util.List;

public interface SqlInsertStatement {

    List<LogicalAttribute> orderedColumns();

    String singleRowSql();

    String multiRowSql(int rows);
}