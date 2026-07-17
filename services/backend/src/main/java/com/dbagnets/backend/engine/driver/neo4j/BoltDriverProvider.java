package com.dbagnets.backend.engine.driver.neo4j;

import org.neo4j.driver.Driver;

public interface BoltDriverProvider {

    Driver get(String databaseId, String host, int port);
}
