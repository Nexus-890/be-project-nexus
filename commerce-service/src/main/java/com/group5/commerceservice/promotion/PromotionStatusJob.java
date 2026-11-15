package com.group5.commerceservice.promotion;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class PromotionStatusJob {
    private final MegaSaleEventService events;
    private final VoucherLifecycleService vouchers;

    PromotionStatusJob(MegaSaleEventService events, VoucherLifecycleService vouchers) {
        this.events = events;
        this.vouchers = vouchers;
    }

    @Scheduled(fixedDelayString = "${nexus.promotion.status-interval:30s}",
            initialDelayString = "${nexus.promotion.status-interval:30s}")
    @SchedulerLock(name = "promotionStatusJob", lockAtMostFor = "PT2M")
    public void run() {
        var now = MegaSaleEventService.now();
        events.advance(now);
        vouchers.expireVoucherWindows(now);
    }
}
