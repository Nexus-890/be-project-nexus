package com.group5.paymentservice.payout.job;

import com.group5.paymentservice.payout.service.PayoutDispatchService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "nexus.payout.jobs-enabled", havingValue = "true")
public class PayoutDispatchJob {
    private final PayoutDispatchService dispatcher;

    @Scheduled(cron = "${nexus.payout.dispatch-cron:*/10 * * * * *}")
    public void run() {
        for (int i = 0; i < 100; i++) {
            try {
                if (!dispatcher.dispatchOne()) break;
            } catch (RuntimeException failure) {
                log.warn("Payout dispatch retained for recovery");
                break;
            }
        }
    }
}
