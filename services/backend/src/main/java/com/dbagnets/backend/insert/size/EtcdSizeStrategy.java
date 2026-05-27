package com.dbagnets.backend.insert.size;

import com.dbagnets.backend.docker.DockerService;

/** etcd: parse {@code dbSize} from {@code etcdctl endpoint status -w fields}. */
public class EtcdSizeStrategy implements DatabaseSizeStrategy {

    @Override
    public long sizeBytes(DockerService docker, String containerId, Integer hostPort) {
        try {
            String out = docker.execInContainer(containerId,
                "sh", "-c", "ETCDCTL_API=3 etcdctl endpoint status --write-out=fields");
            if (out == null) return -1;
            for (String line : out.split("\\R")) {
                String l = line.trim();
                if (l.startsWith("\"DbSize\"") || l.startsWith("DbSize")) {
                    String[] parts = l.split(":");
                    if (parts.length >= 2) {
                        return Long.parseLong(parts[1].trim().replaceAll("[^0-9]", ""));
                    }
                }
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }
}
