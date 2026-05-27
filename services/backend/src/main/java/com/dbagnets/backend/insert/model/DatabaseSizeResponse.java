package com.dbagnets.backend.insert.model;

import java.util.Locale;

/**
 * Per-DB size telemetry for the memory chart.
 *
 * <p>{@code sizeBytes} is the current on-disk footprint. {@code baselineBytes} is what the DB
 * occupied right after the init script finished (engine + schema). {@code dataBytes} is the
 * difference — the actual user-inserted data. The frontend draws the bar as a stack:
 * {@code [baseline][data]}.
 */
public record DatabaseSizeResponse(
    String databaseId,
    String dbName,
    String dbVersion,
    Long sizeBytes,
    Long baselineBytes,
    Long dataBytes,
    String sizeHuman,
    boolean available
) {
    public static DatabaseSizeResponse of(
        String databaseId, String dbName, String dbVersion,
        long sizeBytes, Long baselineBytes
    ) {
        if (sizeBytes < 0) {
            return new DatabaseSizeResponse(databaseId, dbName, dbVersion, null, baselineBytes, null, "n/a", false);
        }
        Long data = baselineBytes == null ? null : Math.max(0L, sizeBytes - baselineBytes);
        return new DatabaseSizeResponse(databaseId, dbName, dbVersion, sizeBytes, baselineBytes, data,
            humanize(sizeBytes), true);
    }

    static String humanize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int i = -1;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.ROOT, "%.2f %s", v, units[i]);
    }
}
