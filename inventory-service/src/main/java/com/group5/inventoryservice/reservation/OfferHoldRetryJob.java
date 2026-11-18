package com.group5.inventoryservice.reservation;

import com.group5.inventoryservice.reservation.service.OfferReservationService;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Component
@RequiredArgsConstructor
@Slf4j
public class OfferHoldRetryJob {
    private final OfferReservationService holds;
    private final Tracer tracer;
    @Scheduled(fixedDelay=5000, initialDelay=5000)
    @SchedulerLock(name="inventoryOfferHoldRetry",lockAtMostFor="PT1M")
    public void tick() {
        for(var id:holds.pending()) {
            var span=tracer.nextSpan().name("inventory.offer-hold-retry").start();
            try(var ignored=tracer.withSpan(span)) { holds.retry(id); }
            catch(RuntimeException failure) { log.error("Offer hold {} retry failed",id,failure); }
            finally { span.end(); }
        }
    }
}
