package com.dbagnets.backend.sse;

public record SseEvent(String type, Object data) {}
