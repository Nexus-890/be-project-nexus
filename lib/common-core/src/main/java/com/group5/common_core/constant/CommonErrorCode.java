package com.group5.common_core.constant;

import org.springframework.http.HttpStatus;

/** Transport errors shared by services; business codes belong to the owning service. */
public enum CommonErrorCode implements ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "The request data is invalid"),
    INVALID_REQUEST_BODY(HttpStatus.BAD_REQUEST, "The request body is invalid or malformed"),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "A request parameter is invalid"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "The HTTP method is not supported for this endpoint"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The request content type is not supported"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "The requested response format is not supported"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "A valid access token is required to access this resource"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "You do not have permission to access this resource"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource was not found"),
    CONFLICT(HttpStatus.CONFLICT, "The request conflicts with the current resource state"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests; retry after the indicated delay"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "A required service is unavailable"),
    GATEWAY_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "The service did not answer in time"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");

    private final HttpStatus status;
    private final String message;
    CommonErrorCode(HttpStatus status, String message) { this.status = status; this.message = message; }
    @Override public String code() { return name(); }
    @Override public HttpStatus status() { return status; }
    @Override public String message() { return message; }

    public static CommonErrorCode forStatus(int status) {
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
