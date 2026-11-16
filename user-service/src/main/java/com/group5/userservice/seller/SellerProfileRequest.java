package com.group5.userservice.seller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SellerProfileRequest(
        @NotBlank @Size(max = 200) String companyName,
        @NotBlank @Size(max = 20) String contactPhone) {
    public SellerProfileRequest {
        companyName = companyName == null ? null : companyName.trim();
        contactPhone = contactPhone == null ? null : contactPhone.trim();
    }
}
