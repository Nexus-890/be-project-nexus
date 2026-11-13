package com.group5.shipmentservice.shipping.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Selects dispatch options; buyer, payment and recipient data come from OrderPaid. */
public record CreateShipmentRequest(
        @NotNull UUID orderId,
        UUID warehouseId,
        @Size(min = 1, max = 40) @Pattern(regexp = "\\S+") String carrier,
        @Size(min = 1, max = 40) @Pattern(regexp = "\\S+") String serviceType) {
}
