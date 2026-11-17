package com.group5.commerceservice.order;

import com.group5.commerceservice.order.repository.OrderRepository;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;

@Service
@Slf4j
public class OrderCompletionJob {
    private final OrderRepository orders;
    private final OrderCompletionService completions;
    private final Tracer tracer;
    private final Clock clock;

    @Value("${nexus.order.auto-complete-batch-size:100}")
    private int batchSize = 100;

    @Autowired
    public OrderCompletionJob(OrderRepository orders, OrderCompletionService completions, Tracer tracer) {
        this(orders, completions, tracer, Clock.systemUTC());
    }

    OrderCompletionJob(OrderRepository orders, OrderCompletionService completions, Tracer tracer, Clock clock) {
        this.orders = orders;
        this.completions = completions;
        this.tracer = tracer;
        this.clock = clock;
    }

    @PostConstruct
    void validateBatchSize() {
        if (batchSize < 1) throw new IllegalArgumentException("nexus.order.auto-complete-batch-size must be at least 1");
    }

    @Scheduled(fixedDelayString = "${nexus.order.auto-complete-interval:10m}",
            initialDelayString = "${nexus.order.auto-complete-interval:10m}")
    @SchedulerLock(name = "orderAutoCompleteJob", lockAtMostFor = "PT9M")
    public void run() { runOnce(); }

    public int runOnce() {
        int completed = 0;
        for (var id : orders.findReceiptDue(OffsetDateTime.now(clock), batchSize)) {
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
