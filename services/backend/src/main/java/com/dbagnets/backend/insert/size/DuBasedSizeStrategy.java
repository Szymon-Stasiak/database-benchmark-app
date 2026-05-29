package com.dbagnets.backend.insert.size;

import com.dbagnets.backend.docker.DockerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Universal fallback: shells out to {@code du -sk <path>} inside the container and
 * multiplies the reported 1K-blocks by 1024. Works on every distro that ships busybox or
 * coreutils — which covers all the official DB images we use.
 */
@RequiredArgsConstructor
@Slf4j
public class DuBasedSizeStrategy implements DatabaseSizeStrategy {

    private final String path;

    @Override
    public long sizeBytes(DockerService docker, String containerId, Integer hostPort) {
        // Try -sB1 first (byte-level granularity on GNU coreutils) — required to see small
        // post-insert deltas like a few KB of Mongo/Neo4j data when the engine's baseline is in
        // the tens of MB. Falls back to -sk (1K blocks) on BusyBox images that don't accept -B1.
        try {
            String out = docker.execInContainer(containerId, "du", "-sB1", path);
            if (out != null && !out.isBlank()) {
                return parseFirstNumber(out);
            }
        } catch (Exception ignored) {
            // fall through to the BusyBox-compatible variant
        }
        try {
            String out = docker.execInContainer(containerId, "du", "-sk", path);
            if (out == null || out.isBlank()) return -1;
            return parseFirstNumber(out) * 1024L;
        } catch (Exception e) {
            log.debug("du failed on {} for path {}: {}", containerId, path, e.getMessage());
            return -1;
        }
    }

    private static long parseFirstNumber(String out) {
        String first = out.split("\\R", 2)[0].trim();
        String number = first.split("\\s+")[0];
        return Long.parseLong(number);
    }
}
