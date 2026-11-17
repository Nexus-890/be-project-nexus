package com.group5.userservice.iam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminPasswordRequest(@NotBlank @Size(min = 8) String newPassword) {
    /** Không đưa mật khẩu vào log hay thông báo lỗi. */
    @Override public String toString() { return "AdminPasswordRequest[redacted]"; }
}
