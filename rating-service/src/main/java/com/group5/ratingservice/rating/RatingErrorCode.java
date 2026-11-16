package com.group5.ratingservice.rating;

import com.group5.common_core.constant.ErrorCode;
import org.springframework.http.HttpStatus;

public enum RatingErrorCode implements ErrorCode {
    RATING_NOT_ELIGIBLE(HttpStatus.CONFLICT, "This order is not available for rating yet"),
    RATING_FORBIDDEN(HttpStatus.FORBIDDEN, "Only the order buyer can rate its seller"),
    RATING_ALREADY_EXISTS(HttpStatus.CONFLICT, "You have already rated this order"),
    RATING_INVALID_RECIPIENT(HttpStatus.BAD_REQUEST, "The recipient must be this order's seller"),
    RATING_INVALID_QUERY(HttpStatus.BAD_REQUEST, "Page must be nonnegative and size must be between 1 and 100"),
    REPUTATION_NOT_FOUND(HttpStatus.NOT_FOUND, "This user's reputation is not available yet");

    private final HttpStatus status;
    private final String message;
    RatingErrorCode(HttpStatus status, String message) { this.status = status; this.message = message; }
    @Override public String code() { return name(); }
    @Override public HttpStatus status() { return status; }
    @Override public String message() { return message; }
}
