package com.group5.paymentservice.payout.event;

import com.group5.paymentservice.payout.repository.ManualPayoutRepository;
import com.group5.paymentservice.payout.service.ManualPayoutService;
import com.group5.paymentservice.sellerbank.event.BankAccountChanged;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class ManualPayoutBankChangeListener {
    private final ManualPayoutRepository repository;
    private final ManualPayoutService payouts;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void changed(BankAccountChanged event) {
        for (var payoutId : repository.issuedBySeller(event.sellerId())) {
            try {
                payouts.recheckBankAfterCommit(payoutId);
            } catch (RuntimeException failure) {
                // Scheduled expiry/readiness recovery rechecks changed bank versions after a crash
                // or failure.
                log.warn("Manual payout bank recheck deferred: payoutId={}", payoutId);
            }
        }
    }
}
