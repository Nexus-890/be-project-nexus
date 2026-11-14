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
    USER_IDENTITY_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The identity provider is unavailable"),
    USER_VERIFICATION_EMAIL_FAILED(HttpStatus.BAD_GATEWAY,
            "The verification email could not be sent; the account is kept, ask for a new email"),
    USER_VERIFICATION_EMAIL_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS,
            "Wait one minute before asking for another verification email"),
    PHONE_CODE_INVALID(HttpStatus.BAD_REQUEST, "The verification code is wrong"),
    PHONE_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "The verification code has expired; ask for a new one"),
    PHONE_CODE_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many codes or attempts; try again later");

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
