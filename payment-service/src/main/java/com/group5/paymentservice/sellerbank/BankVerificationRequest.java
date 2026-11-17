package com.group5.paymentservice.sellerbank;

public record BankVerificationRequest(String verificationStatus, String reason, Long expectedVersion) { }
