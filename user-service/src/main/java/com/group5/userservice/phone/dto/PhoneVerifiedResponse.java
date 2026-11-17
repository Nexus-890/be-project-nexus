package com.group5.userservice.phone.dto;

import java.time.OffsetDateTime;

public record PhoneVerifiedResponse(String phone, OffsetDateTime phoneVerifiedAt) {
}
