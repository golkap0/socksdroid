package net.typeblog.socks.util;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class BufferPool {
    private static final int BUFFER_SIZE = 16384;
    private static final int MAX_POOL_SIZE = 20;
    private static final ConcurrentLinkedQueue<byte[]> pool = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger poolSize = new AtomicInteger(0);

    public static byte[] get() {
        byte[] buffer = pool.poll();
        if (buffer == null) {
            return new byte[BUFFER_SIZE];
        }
        poolSize.decrementAndGet();
        return buffer;
    }

    public static void release(byte[] buffer) {
        if (buffer != null && buffer.length == BUFFER_SIZE && poolSize.get() < MAX_POOL_SIZE) {
            pool.offer(buffer);
            poolSize.incrementAndGet();
        }
    }
}
