package com.group5.paymentservice.payout.provider;

import java.math.BigDecimal;
import java.util.UUID;

public interface PayoutGateway {
    record Transfer(UUID payoutId, int attempt, String key, BigDecimal amount, String currency,
            String bankCode, String accountNumber, String holderName) {
        // Không để log tự động làm lộ thông tin tài khoản nhận tiền.
        @Override
        public String toString() {
            return "Transfer[payoutId=" + payoutId + ", attempt=" + attempt + "]";
        }
    }

    enum State { ACCEPTED, SUCCEEDED, FAILED, UNKNOWN, RETURNED, NOT_FOUND }

    record Result(State state, String reference, String providerStatus) { }

    String provider();

    boolean simulated();

    Result send(Transfer transfer);

    Result lookup(String key);
}
