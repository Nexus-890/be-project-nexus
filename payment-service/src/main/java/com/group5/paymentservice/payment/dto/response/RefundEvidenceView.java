package com.group5.paymentservice.payment.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RefundEvidenceView(
        UUID evidenceId,
        String contentType,
        int sizeBytes,
        String bankReference,
        UUID uploadedBy,
        OffsetDateTime uploadedAt) {
}
