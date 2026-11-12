package com.group5.commerceservice.order.client.config;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Ngân sách chung cho tất cả quote và reserve trong một checkout, tự dọn khi request kết thúc. */
public final class OrderRequestDeadline implements AutoCloseable {
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    private final Long previous;

    private OrderRequestDeadline(long timeoutMillis) {
        if (timeoutMillis < 1 || timeoutMillis > 2800) throw new IllegalArgumentException("Invalid checkout deadline");
        previous = DEADLINE.get();
        long requested = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        DEADLINE.set(previous == null ? requested : Math.min(previous, requested));
    }

    public static OrderRequestDeadline start(long timeoutMillis) {
        return new OrderRequestDeadline(timeoutMillis);
    }

    static long remainingMillis(long maximum) throws IOException {
        Long deadline = DEADLINE.get();
        if (deadline == null) return maximum;
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("Checkout dependency deadline exceeded");
        return Math.min(maximum, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
    }

    @Override
    public void close() {
        if (previous == null) DEADLINE.remove();
        else DEADLINE.set(previous);
    }
}
