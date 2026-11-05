package com.group5.auctionservice.shared.exception;

import com.group5.common_core.constant.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AuctionErrorCode implements ErrorCode {
    AUCTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Auction not found"),
    AUCTION_START_IN_PAST("startAt must not be in the past (5 seconds of clock skew allowed)"),
    AUCTION_INVALID_TIME_WINDOW("endAt must be after startAt"),
    AUCTION_DURATION_TOO_SHORT("Auction duration is below the configured minimum"),
    AUCTION_DURATION_TOO_LONG("Auction duration exceeds the configured maximum");

    private final String message;
    private final HttpStatus status;

    AuctionErrorCode(String message) {
        this(HttpStatus.BAD_REQUEST, message);
    }

    AuctionErrorCode(HttpStatus status, String message) {
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
