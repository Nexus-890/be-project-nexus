package com.group5.shipmentservice.shipping.adapter.ghn.dto.request;

public record GhnParcelItemRequest(String name, Integer quantity, Integer weight,
                                  Integer length, Integer width, Integer height) {
}
