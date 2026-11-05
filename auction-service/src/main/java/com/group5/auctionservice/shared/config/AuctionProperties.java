package com.group5.auctionservice.shared.config;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

@Validated
@ConfigurationProperties("nexus.auction")
public record AuctionProperties(
        @DefaultValue("60") @Positive int minDurationMinutes,
        @DefaultValue("168") @Positive int maxDurationHours,
        @DefaultValue("10") @Positive @Digits(integer = 17, fraction = 2) BigDecimal defaultBidIncrement
) {
    public AuctionProperties {
        if (Duration.ofMinutes(minDurationMinutes).compareTo(Duration.ofHours(maxDurationHours)) > 0) {
            throw new IllegalArgumentException("nexus.auction.min-duration-minutes exceeds the maximum duration");
        }
    }
}
