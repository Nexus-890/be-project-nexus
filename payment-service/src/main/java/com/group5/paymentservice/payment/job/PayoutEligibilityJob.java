package com.group5.paymentservice.payment.job;

import com.group5.paymentservice.payment.service.PayoutEligibilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PayoutEligibilityJob {
    private final PayoutEligibilityService payouts;

    @Scheduled(cron = "${nexus.payment.payout-eligibility.cron:*/30 * * * * *}")
    public void run() {
        for (var id : payouts.pendingIds()) {
            try { payouts.retry(id); }
            catch (RuntimeException failure) { log.warn("Payout eligibility retained for retry: orderId={}", id); }
        }
    }
}
