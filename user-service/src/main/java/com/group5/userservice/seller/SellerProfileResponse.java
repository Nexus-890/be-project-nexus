package com.group5.userservice.seller;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public record SellerProfileResponse(UUID userId, String companyName, String contactPhone, SellerStatus status,
        String applicationNote, String rejectionReason, OffsetDateTime appliedAt, UUID reviewedBy,
        OffsetDateTime reviewedAt, long version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    public static SellerProfileResponse from(Seller seller) {
        return new SellerProfileResponse(seller.getUserId(), seller.getCompanyName(), seller.getContactPhone(),
                seller.getStatus(), seller.getApplicationNote(), seller.getRejectionReason(), utc(seller.getAppliedAt()),
                seller.getReviewedBy(), utc(seller.getReviewedAt()), seller.getVersion(),
                utc(seller.getCreatedAt()), utc(seller.getUpdatedAt()));
    }

    private static OffsetDateTime utc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }
}
