package com.group5.shipmentservice.shared.config;

import com.group5.shipmentservice.shipping.adapter.model.PickupAddress;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Validated
@ConfigurationProperties(prefix = "nexus.shipping")
public record ShippingProperties(
        @DefaultValue("NONE") CarrierMode carrier,
        @DefaultValue("3000") @Min(100) int apiTimeoutMs,
        @DefaultValue("3") @Min(0) @Max(3) int retryLimit,
        @DefaultValue("100") @Min(0) int retryBackoffMs,
        @DefaultValue("1000000") @DecimalMin("0") BigDecimal freeShippingThreshold,
        @DefaultValue("5") @Min(1) int breakerFailureThreshold,
        @DefaultValue("30000") @Min(1) int breakerOpenMs,
        @DefaultValue("") String catalogBaseUrl,
        UUID defaultWarehouseId,
        @DefaultValue PickupAddress defaultPickup,
        Map<UUID, PickupAddress> pickups,
        CarrierMode quoteCarrier,
        CarrierMode bookingCarrier) {
    @ConstructorBinding
    public ShippingProperties {
        pickups = pickups == null ? Map.of() : Map.copyOf(pickups);
    }

    public ShippingProperties(CarrierMode carrier, int apiTimeoutMs, int retryLimit, int retryBackoffMs,
                              BigDecimal freeShippingThreshold, int breakerFailureThreshold, int breakerOpenMs,
                              String catalogBaseUrl, UUID defaultWarehouseId, PickupAddress defaultPickup,
                              Map<UUID, PickupAddress> pickups) {
        this(carrier, apiTimeoutMs, retryLimit, retryBackoffMs, freeShippingThreshold, breakerFailureThreshold,
                breakerOpenMs, catalogBaseUrl, defaultWarehouseId, defaultPickup, pickups, null, null);
    }

    public CarrierMode effectiveQuoteCarrier() { return quoteCarrier == null ? carrier : quoteCarrier; }

    public CarrierMode effectiveBookingCarrier() { return bookingCarrier == null ? carrier : bookingCarrier; }

    public enum CarrierMode {
        NONE,
        FAKE,
        GHN
    }
}
