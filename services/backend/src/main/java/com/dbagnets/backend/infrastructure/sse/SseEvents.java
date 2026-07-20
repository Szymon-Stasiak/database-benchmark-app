package com.dbagnets.backend.infrastructure.sse;

import java.util.Map;

public interface SseEvents {

    String EVENT_BENCHMARK_STATUS = "benchmark_status";
    String EVENT_DATABASE_STATUS = "database_status";
    String EVENT_DATABASE_PORT_ASSIGNED = "database_port_assigned";
    String EVENT_SCRIPT_GENERATED = "script_generated";
    String EVENT_DATABASE_SIZE_DIRTY = "database_size_dirty";

    String EVENT_INSERT_RUN_STATUS = "insert_run_status";
    String EVENT_INSERT_RESULT_STATUS = "insert_result_status";
    String EVENT_INSERT_BATCH_PROGRESS = "insert_batch_progress";
    String EVENT_READ_RUN_STATUS = "read_run_status";
    String EVENT_READ_RUN_PREPARED = "read_run_prepared";
    String EVENT_READ_RESULT_STATUS = "read_result_status";
    String EVENT_DELETE_RUN_STATUS = "delete_run_status";
    String EVENT_DELETE_RUN_PREPARED = "delete_run_prepared";
    String EVENT_DELETE_RESULT_STATUS = "delete_result_status";

    String EVENT_CONTAINER_STATS = "container_stats";

    String EVENT_SCENARIO_RUN_STATUS = "scenario_run_status";
    String EVENT_SCENARIO_RUN_PREPARED = "scenario_run_prepared";
    String EVENT_SCENARIO_RESULT_STATUS = "scenario_result_status";

    String EVENT_HEARTBEAT = "heartbeat";
    String EVENT_LOG = "log";

    String PAYLOAD_BENCHMARK_ID = "benchmarkId";
    String PAYLOAD_DATABASE_ID = "databaseId";
    String PAYLOAD_STATUS = "status";
    String PAYLOAD_ERROR_MESSAGE = "errorMessage";
    String PAYLOAD_HOST_PORT = "hostPort";
    String PAYLOAD_SCRIPT_PREVIEW = "scriptPreview";

    long INITIAL_STATE_DELAY_MS = 50L;

    static Map<String, Object> benchmarkStatusPayload(String benchmarkId, Object status) {
        return Map.of(PAYLOAD_BENCHMARK_ID, benchmarkId, PAYLOAD_STATUS, status);
    }

    static Map<String, Object> databaseStatusPayload(String benchmarkId, String databaseId, Object status) {
        return Map.of(
                PAYLOAD_BENCHMARK_ID, benchmarkId,
                PAYLOAD_DATABASE_ID, databaseId,
                PAYLOAD_STATUS, status
        );
    }

    static Map<String, Object> databaseStatusPayload(String benchmarkId, String databaseId, Object status, String errorMessage) {
        if (errorMessage == null) {
            return databaseStatusPayload(benchmarkId, databaseId, status);
        }
        return Map.of(
                PAYLOAD_BENCHMARK_ID, benchmarkId,
                PAYLOAD_DATABASE_ID, databaseId,
                PAYLOAD_STATUS, status,
                PAYLOAD_ERROR_MESSAGE, errorMessage
        );
    }

    static Map<String, Object> databasePortAssignedPayload(String benchmarkId, String databaseId, int hostPort) {
        return Map.of(
                PAYLOAD_BENCHMARK_ID, benchmarkId,
                PAYLOAD_DATABASE_ID, databaseId,
                PAYLOAD_HOST_PORT, hostPort
        );
    }
}