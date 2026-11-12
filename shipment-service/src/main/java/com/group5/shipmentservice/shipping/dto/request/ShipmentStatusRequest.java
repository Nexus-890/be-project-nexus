package com.group5.shipmentservice.shipping.dto.request;

import com.group5.shipmentservice.shipping.enums.ShipmentStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ShipmentStatusRequest(@NotNull ShipmentStatus status, @Size(max = 255) String reason) { }
