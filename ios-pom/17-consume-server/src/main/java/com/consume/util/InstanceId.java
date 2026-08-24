package com.consume.util;

import java.net.InetAddress;
import java.util.UUID;

/**
 * 多实例部署时的进程级标识（主机名-pid），用于 Redis Stream consumer name 等。
 */
public final class InstanceId {

    private static final String VALUE = build();

    private InstanceId() {
    }

    public static String get() {
        return VALUE;
    }

    private static String build() {
        String host = "unknown";
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignore) {
            // keep unknown
        }
        if (host == null || host.isEmpty()) {
            host = "unknown";
        }
        host = host.replaceAll("[^A-Za-z0-9._-]", "_");
        long pid = 0L;
        try {
            pid = ProcessHandle.current().pid();
        } catch (Exception ignore) {
            pid = UUID.randomUUID().toString().hashCode() & 0x7fffffff;
        }
        return host + "-" + pid;
    }
}
