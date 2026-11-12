package com.group5.shipmentservice.shipping.dto.response;

public record CarrierResponse(String code, String name, boolean isDefault, boolean available) {
}
