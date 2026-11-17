package com.group5.userservice.seller;

import java.util.UUID;

public record SellerApplicationResponse(UUID id, String companyName, String phone, String note,
        SellerStatus status, String reason) {
    public static SellerApplicationResponse from(Seller seller) {
        return new SellerApplicationResponse(seller.getUserId(), seller.getCompanyName(), seller.getContactPhone(),
                seller.getApplicationNote() == null ? "" : seller.getApplicationNote(), seller.getStatus(),
                seller.getStatus() == SellerStatus.REJECTED ? seller.getRejectionReason() : null);
    }
}
