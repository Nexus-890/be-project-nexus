package com.group5.paymentservice.payout;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

@Getter
@Setter
@Validated
@ConfigurationProperties("nexus.payout")
public class PayoutProperties {
    @NotNull
    @DecimalMin("0")
    @DecimalMax("1")
    @Digits(integer = 1, fraction = 4)
    private BigDecimal platformFeeRate = BigDecimal.ZERO;
    @NotBlank
    private String provider = "sandbox";
    @Min(1)
    private int maxAttempts = 3;
    @NotNull
    private Duration retryBackoff = Duration.ofMinutes(1);
    @NotNull
    private Duration reconcileInterval = Duration.ofMinutes(1);
    @NotNull
    private Duration providerTimeout = Duration.ofSeconds(10);
    private String sandboxOutcome = "SUCCESS";
    private String callbackSecret = "";
    private String providerUrl = "";
    private String providerToken = "";

    @AssertTrue(message = "Payout durations must be positive and provider must be sandbox or http")
    public boolean isValidConfiguration() {
        return positive(retryBackoff) && positive(reconcileInterval) && positive(providerTimeout)
                && ("sandbox".equals(provider) || "http".equals(provider))
                && java.util.Set.of("SUCCESS", "FAILURE", "TIMEOUT", "RETURN", "ACCEPTED").contains(sandboxOutcome)
                && (!"http".equals(provider) || (providerUrl.startsWith("https://")
                    && !providerToken.isBlank() && !callbackSecret.isBlank()));
    }

    private boolean positive(Duration d) {
        return d != null && !d.isZero() && !d.isNegative();
    }
}
