package com.group5.paymentservice.payout.job;

import com.group5.paymentservice.payout.service.PayoutDispatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "nexus.payout.jobs-enabled", havingValue = "true", matchIfMissing = true)
public class PayoutReconcileJob {
    private final PayoutDispatchService dispatcher;

    @Scheduled(fixedDelayString = "${nexus.payout.reconcile-interval:PT1M}")
    public void run() {
        dispatcher.reconcile();
    }
}
