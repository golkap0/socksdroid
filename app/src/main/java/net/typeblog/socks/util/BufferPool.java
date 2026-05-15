package net.typeblog.socks.util;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class BufferPool {
    private static final int BUFFER_SIZE = 65536;
    private static final int MAX_POOL_SIZE = 100;
    private static final ConcurrentLinkedQueue<byte[]> pool = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger currentSize = new AtomicInteger(0);

    public static byte[] obtain() {
        byte[] buffer = pool.poll();
        if (buffer != null) {
            currentSize.decrementAndGet();
            return buffer;
        }
        return new byte[BUFFER_SIZE];
    }

    public static void recycle(byte[] buffer) {
        if (buffer != null && buffer.length == BUFFER_SIZE && currentSize.get() < MAX_POOL_SIZE) {
            pool.offer(buffer);
            currentSize.incrementAndGet();
        }
    }
}
