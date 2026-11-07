package com.group5.commerceservice.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** The accepted shipment transition shared by ShipmentPickedUp and ShipmentDelivered. */
public record ShipmentStatusChanged(
        @NotNull UUID shipmentId,
        @NotNull UUID orderId,
        @NotNull UUID buyerId,
        @NotNull UUID warehouseId,
        @NotBlank @Size(max = 40) String carrier,
        @NotBlank @Size(max = 80) String trackingCode,
        @NotBlank String status,
        @NotNull UUID historyId,
        @NotNull @PositiveOrZero Long shipmentVersion,
        @NotNull @Pattern(regexp = "SYSTEM|CARRIER_WEBHOOK|POLLER|MANUAL") String source,
        @Size(max = 160) @Pattern(regexp = "(?s).*\\S.*") String carrierEventId,
        @NotNull UUID actorId,
        @NotBlank @Pattern(regexp = ".*Z$") String changedAt,
        @Size(max = 255) @Pattern(regexp = "(?s).*\\S.*") String reason) {
}
