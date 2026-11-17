package com.group5.userservice.seller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectSellerApplicationRequest(@NotBlank @Size(max = 1000) String reason) {
    public RejectSellerApplicationRequest {
        reason = reason == null ? null : reason.trim();
    }
}
