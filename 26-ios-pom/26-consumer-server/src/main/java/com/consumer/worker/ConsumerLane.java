package com.consumer.worker;

/**
 * 当前消费线程属于哪条车道。V1 工人设置后，扫包池和查链池走 V1，不占用 V2 的线程。
 */
public final class ConsumerLane {

    private static final ThreadLocal<Boolean> V1 = new ThreadLocal<>();

    private ConsumerLane() {
    }

    public static boolean isV1() {
        return Boolean.TRUE.equals(V1.get());
    }

    public static void runAsV1(Runnable work) {
        V1.set(Boolean.TRUE);
        try {
            work.run();
        } finally {
            V1.remove();
        }
    }
}
