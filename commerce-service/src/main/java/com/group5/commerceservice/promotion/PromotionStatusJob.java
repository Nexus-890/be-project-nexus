package com.group5.commerceservice.promotion;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class PromotionStatusJob {
    private final MegaSaleEventService events;

    PromotionStatusJob(MegaSaleEventService events) {
        this.events = events;
    }

    @Scheduled(fixedDelayString = "${nexus.promotion.status-interval:30s}",
            initialDelayString = "${nexus.promotion.status-interval:30s}")
    @SchedulerLock(name = "promotionStatusJob", lockAtMostFor = "PT2M")
    public void run() {
        events.advance(MegaSaleEventService.now());
    }
}
