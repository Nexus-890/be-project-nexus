package com.group5.paymentservice.sellerbank.dto.request;

public record BankVerificationRequest(String verificationStatus, String reason, Long expectedVersion) { }
