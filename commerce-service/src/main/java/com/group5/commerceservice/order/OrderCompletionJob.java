package com.group5.commerceservice.order;

import com.group5.commerceservice.order.repository.OrderRepository;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderCompletionJob {
    private final OrderRepository orders;
    private final OrderCompletionService completions;
    private final Tracer tracer;

    @Scheduled(fixedDelayString = "${nexus.order.auto-complete-interval:1m}",
            initialDelayString = "${nexus.order.auto-complete-interval:1m}")
    public void run() { runOnce(); }

    public int runOnce() {
        int completed = 0;
        for (var id : orders.findReceiptDue(OffsetDateTime.now(ZoneOffset.UTC))) {
            var span = tracer.nextSpan().name("order-auto-complete").start();
            try (var ignored = tracer.withSpan(span)) {
                if (completions.completeIfDue(id)) completed++;
            } catch (RuntimeException failure) {
                log.warn("Automatic completion failed; order retained for retry: {}", id);
            } finally {
                span.end();
            }
        }
        return completed;
    }
}
