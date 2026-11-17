package com.group5.ratingservice.shared.exception;

import com.group5.common_core.constant.ErrorCode;
import org.springframework.http.HttpStatus;

public enum RatingErrorCode implements ErrorCode {
    ORDER_NOT_COMPLETED(HttpStatus.BAD_REQUEST, "The completed order is not available for rating"),
    SELF_RATING(HttpStatus.BAD_REQUEST, "Users cannot rate themselves"),
    NOT_ORDER_PARTICIPANT(HttpStatus.FORBIDDEN, "Only the order participants may submit a rating"),
    ALREADY_EXISTS(HttpStatus.CONFLICT, "A rating for this order has already been submitted"),
    ADJUSTMENT_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "The adjusted reputation score must remain between 0 and 100"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "The idempotency key has already been used for another adjustment"),
    RECONCILIATION_REQUIRED(HttpStatus.SERVICE_UNAVAILABLE, "Historical reputation inputs must be reconciled before this operation"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "The request data is invalid"),
    INVALID_REQUEST_BODY(HttpStatus.BAD_REQUEST, "The request body is invalid"),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "The request parameter is invalid"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "A valid access token is required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access is denied"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource does not exist"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "The HTTP method is not supported"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "The response format is not supported"),
    CONFLICT(HttpStatus.CONFLICT, "The request conflicts with the current state"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The request media type is not supported"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The required service is unavailable"),
    GATEWAY_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "The required service did not respond in time"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected rating service error occurred");

    private final HttpStatus status;
    private final String message;

    RatingErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public String code() {
        return "RATING_" + name();
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String message() {
        return message;
    }

    public static RatingErrorCode forStatus(int status) {
        return switch (status) {
            case 400 -> INVALID_PARAMETER;
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> CONFLICT;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 429 -> RATE_LIMITED;
            case 503 -> SERVICE_UNAVAILABLE;
            case 504 -> GATEWAY_TIMEOUT;
            default -> INTERNAL_ERROR;
        };
    }
}
