package com.consumer.util;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Stream poller 回压：worker 队列超过高水位时暂停 XREAD/XAUTOCLAIM，
 * 消息留在 Redis Stream（未投递），不丢、不进 PEL 风暴。
 */
public final class StreamBackpressure {

    private StreamBackpressure() {}

    /**
     * @param highRatio 0~1，队列占用达到该比例则应暂停拉取（建议 0.5）
     */
    public static boolean shouldPause(ThreadPoolExecutor exec, double highRatio) {
        if (exec == null) return false;
        double ratio = highRatio;
        if (ratio <= 0) ratio = 0.5;
        if (ratio > 0.95) ratio = 0.95;
        BlockingQueue<Runnable> q = exec.getQueue();
        int size = q.size();
        int cap = size + q.remainingCapacity();
        if (cap <= 0) return false;
        return size >= (int) Math.ceil(cap * ratio);
    }

    public static int queueSize(ThreadPoolExecutor exec) {
        return exec == null ? 0 : exec.getQueue().size();
    }
}
