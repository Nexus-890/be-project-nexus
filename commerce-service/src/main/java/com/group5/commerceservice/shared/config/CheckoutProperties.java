package com.group5.commerceservice.shared.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "nexus.checkout")
public class CheckoutProperties {
    @AssertTrue(message = "CHECKOUT_PRICE_REVALIDATION must remain true (BR-103)")
    private boolean priceRevalidation = true;

    @NotNull
    @DecimalMin("0")
    private BigDecimal freeShippingThreshold = new BigDecimal("1000000");
}
