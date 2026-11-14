package com.group5.userservice.phone.dto;

import java.time.OffsetDateTime;

public record PhoneCodeSentResponse(String phone, OffsetDateTime expiresAt) {
}
