package com.group5.commerceservice.order;

import com.group5.commerceservice.order.repository.OrderGroupRepository;
import com.group5.commerceservice.order.service.OrderService;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderExpiryJob {
    private final OrderGroupRepository groups;
    private final OrderService orders;
    private final Tracer tracer;

    @Value("${nexus.order.expiry-batch-size:100}")
    private int batchSize = 100;

    @Scheduled(fixedDelayString = "${nexus.order.expiry-interval:30s}",
            initialDelayString = "${nexus.order.expiry-interval:30s}")
    @SchedulerLock(name = "orderExpiryJob", lockAtMostFor = "PT5M")
    public void run() {
        runOnce();
    }

    public int runOnce() {
        int expired = 0;
        for (UUID groupId : groups.findOverdueIds(batchSize)) {
            try {
                if (expireGroup(groupId)) {
                    expired++;
                }
            } catch (RuntimeException failure) {
                log.warn("Order group expiry failed, retried on the next run: groupId={}", groupId, failure);
            }
        }
        if (expired > 0) {
            log.info("Order expiry job completed: expiredGroups={}", expired);
        }
        return expired;
    }

    private boolean expireGroup(UUID groupId) {
        if (tracer.currentSpan() != null) return !orders.expire(groupId).isEmpty();
        Span span = tracer.nextSpan().name("commerce-order-expiry").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return !orders.expire(groupId).isEmpty();
        } finally {
            span.end();
        }
    }
}
