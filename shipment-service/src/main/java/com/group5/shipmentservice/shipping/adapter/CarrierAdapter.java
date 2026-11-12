package com.group5.shipmentservice.shipping.adapter;

import com.group5.shipmentservice.shipping.adapter.model.CarrierQuoteRequest;
import com.group5.shipmentservice.shipping.dto.response.LocalityResponse;
import com.group5.shipmentservice.shipping.dto.response.ShippingQuoteResponse;
import com.group5.shipmentservice.shipping.resilience.ShippingDeadline;
import java.util.List;

public interface CarrierAdapter {
    String code();

    String name();

    List<LocalityResponse> getProvinces(ShippingDeadline deadline);

    List<LocalityResponse> getWards(String provinceCode, ShippingDeadline deadline);

    List<ShippingQuoteResponse> quote(CarrierQuoteRequest request, ShippingDeadline deadline);
}
