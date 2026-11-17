package com.group5.shipmentservice.shipping.adapter.model;

import java.time.OffsetDateTime;

/** Booking facts returned by a carrier, including facts needed for shipment history. */
public record CarrierShipmentResult(String trackingCode, OffsetDateTime estimatedDeliveryAt,
                                    boolean addressFallback) {
}
