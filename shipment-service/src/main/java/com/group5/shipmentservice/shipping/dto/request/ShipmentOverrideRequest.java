package com.group5.shipmentservice.shipping.dto.request;

import com.group5.shipmentservice.shipping.enums.ShipmentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ShipmentOverrideRequest(@NotNull ShipmentStatus status,
                                      @NotBlank @Size(max = 255) String reason) { }
