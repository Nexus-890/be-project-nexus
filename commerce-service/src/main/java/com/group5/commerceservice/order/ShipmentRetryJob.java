package com.group5.commerceservice.order;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
public class ShipmentRetryJob {
    private final PendingShipmentEvents pending;
    private final ShipmentStatusHandler handler;
    private final TransactionTemplate transaction;
    private final Tracer tracer;

    public ShipmentRetryJob(PendingShipmentEvents pending, ShipmentStatusHandler handler,
                            PlatformTransactionManager transactions, Tracer tracer) {
        this.pending = pending;
        this.handler = handler;
        this.transaction = new TransactionTemplate(transactions);
        this.tracer = tracer;
    }

    @Scheduled(fixedDelayString = "${nexus.order.shipment-retry-interval:5s}",
            initialDelayString = "${nexus.order.shipment-retry-interval:5s}")
    public void run() { runOnce(); }

    public int runOnce() {
        int completed = 0;
        for (var eventId : pending.pendingIds()) {
            Span span = tracer.nextSpan().name("order-shipment-retry").start();
            try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                boolean applied = Boolean.TRUE.equals(transaction.execute(status -> pending.lock(eventId).map(event -> {
                    if (!handler.retry(event)) return false;
                    pending.remove(eventId);
                    return true;
                }).orElse(false)));
                if (applied) completed++;
            } catch (RuntimeException failure) {
                // The inbox row and all effects roll back together. Retain it for the next scan.
                log.warn("Shipment prerequisite retry failed; retained pending eventId={}", eventId);
            } finally {
                span.end();
            }
        }
        return completed;
    }
}
