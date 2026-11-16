package com.group5.auctionservice.shared.exception;

import com.group5.common_core.constant.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AuctionErrorCode implements ErrorCode {
    AUCTION_START_IN_PAST(HttpStatus.BAD_REQUEST, "startAt must not be in the past (5 seconds of clock skew allowed)"),
    AUCTION_INVALID_TIME_WINDOW(HttpStatus.BAD_REQUEST, "endAt must be after startAt"),
    AUCTION_DURATION_TOO_SHORT(HttpStatus.BAD_REQUEST, "Auction duration is below the configured minimum"),
    AUCTION_DURATION_TOO_LONG(HttpStatus.BAD_REQUEST, "Auction duration exceeds the configured maximum"),
    AUCTION_SKU_ALREADY_LIVE(HttpStatus.CONFLICT, "This SKU already has a live auction"),
    AUCTION_SELLER_LIMIT_REACHED(HttpStatus.CONFLICT, "The seller has reached the live auction limit"),
    AUCTION_BID_NOT_FOUND(HttpStatus.NOT_FOUND, "Bid not found"),
    AUCTION_STREAM_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "Auction stream connection limit reached"),
    AUCTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Auction not found"),
    AUCTION_PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "Product not found in catalog"),
    AUCTION_SKU_NOT_FOUND(HttpStatus.NOT_FOUND, "SKU not found on the selected product"),
    AUCTION_CATALOG_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Catalog is temporarily unavailable"),
    AUCTION_INVENTORY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Inventory is temporarily unavailable"),
    AUCTION_STOCK_UNAVAILABLE(HttpStatus.CONFLICT, "No stock is available for the selected SKU"),
    AUCTION_IMAGE_REQUIRED(HttpStatus.CONFLICT, "The selected product requires a valid image URL"),
    AUCTION_FORBIDDEN(HttpStatus.FORBIDDEN, "This actor cannot perform the auction action"),
    AUCTION_ILLEGAL_TRANSITION(HttpStatus.CONFLICT, "The auction cannot move to this status from its current status"),
    AUCTION_NOT_EDITABLE(HttpStatus.CONFLICT, "Only an auction that has not started can be configured"),
    AUCTION_HAS_BIDS(HttpStatus.CONFLICT, "The auction has valid bids"),
    AUCTION_BID_TOO_LOW(HttpStatus.BAD_REQUEST, "Bid amount is below the minimum accepted bid"),
    AUCTION_IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key is required"),
    AUCTION_INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "Idempotency-Key must be a UUID"),
    AUCTION_IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "Idempotency-Key was already used for another auction, bidder or amount"),
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
