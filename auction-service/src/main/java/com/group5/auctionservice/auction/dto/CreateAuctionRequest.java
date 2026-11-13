package com.group5.auctionservice.auction.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreateAuctionRequest(
        @NotNull UUID productId,
        @NotNull UUID skuId,
        @Size(max = 255) String title,
        @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal startingPrice,
        @Positive @Digits(integer = 17, fraction = 2) BigDecimal bidIncrement,
        @NotNull Instant startAt,
        @NotNull Instant endAt
) {
}
