package com.group5.paymentservice.sellerbank;

import java.util.UUID;

/** Phát trong transaction xác minh để giải phóng payout ON_HOLD cùng lúc commit. */
public record BankAccountVerified(UUID sellerId) { }
