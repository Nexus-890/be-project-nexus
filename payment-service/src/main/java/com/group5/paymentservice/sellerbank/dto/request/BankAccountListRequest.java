package com.group5.paymentservice.sellerbank.dto.request;

import com.group5.paymentservice.sellerbank.enums.BankVerificationStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.UUID;

public record BankAccountListRequest(UUID sellerId, BankVerificationStatus verificationStatus,
        @Min(0) Integer page, @Min(1) @Max(100) Integer size) {
    public BankAccountListRequest {
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
    }
}
