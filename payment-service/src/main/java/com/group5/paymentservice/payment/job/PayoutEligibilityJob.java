package com.group5.paymentservice.payment.job;

import com.group5.paymentservice.payment.service.PayoutEligibilityService;
import com.group5.paymentservice.payment.repository.PayoutEligibilityRepository.CompletionCursor;
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
        var upperBound = payouts.pendingUpperBound();
        if (upperBound.isEmpty()) return;
        CompletionCursor cursor = null;
        // Chốt cuối hàng đợi để mỗi lượt hữu hạn; vẫn đi qua bản ghi bị từ chối hoặc lỗi để không chặn lô sau.
        while (true) {
            var batch = payouts.pendingBatch(cursor, upperBound.get());
            if (batch.isEmpty()) return;
            for (var completion : batch) {
                try { payouts.retry(completion.orderId()); }
                catch (RuntimeException failure) {
                    log.warn("Payout eligibility retained for retry: orderId={}", completion.orderId());
                }
            }
            cursor = batch.getLast();
        }
    }
}
