package com.group5.shipmentservice.shipping.enums;

public enum ShippingServiceType {
    STANDARD,
    HEAVY;

    public static ShippingServiceType forWeight(int weightGrams) {
        return weightGrams < 20_000 ? STANDARD : HEAVY;
    }
}
