package com.group5.auctionservice.offer;

import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix="nexus.auction.scheduler",name="enabled",matchIfMissing=true)
public class SecondChanceOfferExpiryJob {
    private final SecondChanceOfferService offers;
    private final Tracer tracer;
    @Scheduled(fixedDelay=10_000, scheduler="auctionLifecycleScheduler")
    @SchedulerLock(name="secondChanceOfferExpiry")
    public void tick() {
        for (var id : offers.due()) {
            var span=tracer.nextSpan().name("auction.offer-expire").start();
            try(var ignored=tracer.withSpan(span)) { offers.expire(id); }
            catch(RuntimeException failure) { log.error("Offer {} expiry failed; retry next run",id,failure); }
            finally { span.end(); }
        }
    }
}
