package com.group5.paymentservice.payout.qr;

import java.math.BigDecimal;

public interface PayoutQrEncoder {
    byte[] encode(String bankBin, String accountNumber, BigDecimal amount, String reference);
}
