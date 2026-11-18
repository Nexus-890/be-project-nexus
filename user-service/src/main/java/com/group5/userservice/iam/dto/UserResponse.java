package com.group5.userservice.iam.dto;

import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.enums.Gender;
import com.group5.userservice.iam.enums.UserStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String phone,
        String fullName,
        Gender gender,
        String address,
        LocalDate dateOfBirth,
        List<String> roles,
        UserStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime phoneVerifiedAt,
        String identityNumberMasked
) {
    public static UserResponse from(User user, List<String> roles) {
        return new UserResponse(
                user.getId(), user.getEmail(), user.getPhone(), user.getFullName(),
                user.getGender(), user.getAddress(), user.getDateOfBirth(),
                roles.stream().sorted().toList(),
                user.getStatus(), user.getCreatedAt(), user.getPhoneVerifiedAt(),
                mask(user.getIdentityNumber()));
    }

    static String mask(String identityNumber) {
        if (identityNumber == null || identityNumber.isBlank()) {
            return null;
        }
        int visible = Math.min(4, identityNumber.length() / 2);
        return "*".repeat(identityNumber.length() - visible) + identityNumber.substring(identityNumber.length() - visible);
    }
}
