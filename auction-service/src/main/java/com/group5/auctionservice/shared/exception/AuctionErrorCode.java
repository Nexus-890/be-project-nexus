package com.group5.auctionservice.shared.exception;

import com.group5.common_core.constant.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AuctionErrorCode implements ErrorCode {
    AUCTION_START_IN_PAST(HttpStatus.BAD_REQUEST, "startAt must not be in the past (5 seconds of clock skew allowed)"),
    AUCTION_INVALID_TIME_WINDOW(HttpStatus.BAD_REQUEST, "endAt must be after startAt"),
    AUCTION_DURATION_TOO_SHORT(HttpStatus.BAD_REQUEST, "Auction duration is below the configured minimum"),
    AUCTION_DURATION_TOO_LONG(HttpStatus.BAD_REQUEST, "Auction duration exceeds the configured maximum"),
    AUCTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Auction not found"),
    AUCTION_BID_TOO_LOW(HttpStatus.BAD_REQUEST, "Bid amount is below the minimum accepted bid"),
    AUCTION_NOT_ACTIVE(HttpStatus.CONFLICT, "Auction is not ACTIVE"),
    AUCTION_SELLER_CANNOT_BID(HttpStatus.CONFLICT, "The seller cannot bid on their own auction");

    private final HttpStatus status;
    private final String message;

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
