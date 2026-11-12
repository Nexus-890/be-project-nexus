package com.group5.auctionservice.auction.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;

public record UpdateAuctionRequest(
        @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal startingPrice,
        @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal bidIncrement,
        @NotNull Instant startAt,
        @NotNull Instant endAt
) { }
