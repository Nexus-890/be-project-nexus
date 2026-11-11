package com.group5.shipmentservice.shipping.adapter.model;

import com.group5.shipmentservice.shipping.dto.request.ParcelRequest;
import java.math.BigDecimal;

public record CarrierQuoteRequest(CarrierAddress pickup, CarrierAddress toAddress,
                                  ParcelRequest parcel, BigDecimal insuranceValue,
                                  String recipientName, String recipientPhone, String content) {
}
