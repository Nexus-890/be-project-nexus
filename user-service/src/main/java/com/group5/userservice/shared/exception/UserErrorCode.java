package com.group5.userservice.shared.exception;

import com.group5.common_core.constant.ErrorCode;
import org.springframework.http.HttpStatus;

/** Stable business error identifiers owned by user-service. */
public enum UserErrorCode implements ErrorCode {
    USER_IDENTITY_RECONCILIATION_REQUIRED(HttpStatus.SERVICE_UNAVAILABLE, "The identity change was applied remotely; local completion requires reconciliation"),
    USER_ROLE_INVALID(HttpStatus.BAD_REQUEST, "Role must belong to the fixed application role set"),
    USER_ROLE_NOT_FOUND(HttpStatus.NOT_FOUND, "Role not found"),
    USER_ACCOUNT_NOT_LOCKED(HttpStatus.CONFLICT, "Account is not locked by brute-force detection"),
    USER_IDENTITY_NOT_FOUND(HttpStatus.CONFLICT, "Profile has no corresponding identity-provider account"),
    USER_REGISTRATION_DISABLED(HttpStatus.FORBIDDEN, "Self-registration is disabled"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
    USER_EMAIL_ALREADY_USED(HttpStatus.CONFLICT, "email is already used"),
    USER_PHONE_ALREADY_USED(HttpStatus.CONFLICT, "phone is already used"),
    USER_IDENTITY_NUMBER_ALREADY_USED(HttpStatus.CONFLICT, "identityNumber is already used"),
    USER_IDENTITY_NUMBER_LOCKED(HttpStatus.CONFLICT, "identityNumber is already set and cannot be changed"),
    USER_PROFILE_CONFLICT(HttpStatus.CONFLICT, "Email or phone is already used"),
    USER_REGISTRATION_ROLE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Default registration role is unavailable"),
    USER_IDENTITY_REJECTED(HttpStatus.BAD_REQUEST, "The identity provider rejected the account details"),
    USER_IDENTITY_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The identity provider is unavailable"),
    USER_VERIFICATION_EMAIL_FAILED(HttpStatus.BAD_GATEWAY,
            "The verification email could not be sent; the account is kept, ask for a new email"),
    USER_VERIFICATION_EMAIL_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS,
            "Wait one minute before asking for another verification email"),
    PHONE_CODE_INVALID(HttpStatus.BAD_REQUEST, "The verification code is wrong"),
    PHONE_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "The verification code has expired; ask for a new one"),
    PHONE_CODE_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many codes or attempts; try again later"),
    ADDRESS_LIMIT_REACHED(HttpStatus.CONFLICT, "An account keeps at most 10 addresses"),
    ADDRESS_NOT_FOUND(HttpStatus.NOT_FOUND, "Address not found"),
    SELLER_APPLICATION_EXISTS(HttpStatus.CONFLICT, "An open or approved seller application already exists"),
    SELLER_APPLICATION_NOT_PENDING(HttpStatus.CONFLICT, "Only a pending seller application can be decided"),
    SELLER_APPLICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Seller application not found"),
    USER_AVATAR_REQUIRED(HttpStatus.BAD_REQUEST, "Choose an image file for the avatar"),
    USER_AVATAR_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "The avatar image is larger than the allowed size"),
    USER_AVATAR_INVALID_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The avatar must be a JPEG, PNG or WebP image"),
    USER_AVATAR_STORAGE_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "Avatar storage is not configured"),
    USER_AVATAR_STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Avatar storage is unavailable; try again later"),
    USER_PASSWORD_RESET_LINK_INVALID(HttpStatus.BAD_REQUEST,
            "The password reset link is invalid, used or expired; ask for a new one"),
    USER_PASSWORD_REJECTED(HttpStatus.BAD_REQUEST, "The new password does not meet the password policy"),
    USER_PASSWORD_REUSED(HttpStatus.BAD_REQUEST, "The new password must be different from the current password");

    private final HttpStatus status;
    private final String message;

    UserErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String message() {
        return message;
    }
}
