package com.group5.shipmentservice.shipping.dto.response;

public record PickupAddressCheckResponse(String carrier, String provinceCode, String provinceName,
                                         String wardCode, String wardName, String line1) {
}
