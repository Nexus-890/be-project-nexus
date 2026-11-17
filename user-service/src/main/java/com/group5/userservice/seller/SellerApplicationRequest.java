package com.group5.userservice.seller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SellerApplicationRequest(
        @NotBlank @Size(min = 2, max = 150) String companyName,
        @NotBlank @Pattern(regexp = "^\\+?[0-9]{7,15}$") String phone,
        @NotBlank @Size(min = 10, max = 1000) String note) {
    public SellerApplicationRequest {
        companyName = companyName == null ? null : companyName.trim();
        phone = phone == null ? null : phone.trim();
        note = note == null ? null : note.trim();
    }
}
