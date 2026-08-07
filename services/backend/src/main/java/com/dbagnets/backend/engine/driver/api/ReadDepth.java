package com.dbagnets.backend.engine.driver.api;

public enum ReadDepth {
    NONE, ONE_HOP, FULL_CASCADE;

    public static final int FULL_CASCADE_MAX_DEPTH = 5;

    public static ReadDepth fromIncludeChildren(Boolean includeChildren) {
        return Boolean.TRUE.equals(includeChildren) ? ONE_HOP : NONE;
    }
}