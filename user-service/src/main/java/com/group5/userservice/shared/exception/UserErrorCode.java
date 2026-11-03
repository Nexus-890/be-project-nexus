package com.group5.userservice.shared.exception;

import com.group5.common_core.constant.ErrorCode;
import org.springframework.http.HttpStatus;

/** Stable business error identifiers owned by user-service. */
public enum UserErrorCode implements ErrorCode {
    USER_REGISTRATION_DISABLED(HttpStatus.FORBIDDEN, "Self-registration is disabled"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
    USER_EMAIL_ALREADY_USED(HttpStatus.CONFLICT, "email is already used"),
    USER_PHONE_ALREADY_USED(HttpStatus.CONFLICT, "phone is already used"),
    USER_IDENTITY_NUMBER_ALREADY_USED(HttpStatus.CONFLICT, "identityNumber is already used"),
    USER_PROFILE_CONFLICT(HttpStatus.CONFLICT, "Email or phone is already used"),
    USER_REGISTRATION_ROLE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Default registration role is unavailable"),
    USER_IDENTITY_REJECTED(HttpStatus.BAD_REQUEST, "The identity provider rejected the account details"),
    USER_IDENTITY_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The identity provider is unavailable");

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
