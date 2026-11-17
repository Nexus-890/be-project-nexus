package com.group5.paymentservice.sellerbank.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public record BankAccountView(UUID id, UUID sellerId, String bankCode, String accountLast4,
        String accountHolderName, String verificationStatus, boolean isDefault, boolean active,
        String rejectionReason, UUID verifiedBy, OffsetDateTime verifiedAt,
        OffsetDateTime createdAt, OffsetDateTime updatedAt, long version) { }
