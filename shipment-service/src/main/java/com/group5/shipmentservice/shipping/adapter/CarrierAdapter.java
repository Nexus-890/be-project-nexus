package com.group5.shipmentservice.shipping.adapter;

import com.group5.shipmentservice.shipping.adapter.model.CarrierQuoteRequest;
import com.group5.shipmentservice.shipping.adapter.model.CarrierShipmentRequest;
import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
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

    /** The persisted requestId must be reused after uncertain results and retries. */
    default String createShipment(CarrierShipmentRequest request, ShippingDeadline deadline) {
        throw new DomainException(ShipmentErrorCode.CARRIER_CONFIGURATION_ERROR,
                "Shipment booking is not enabled for carrier " + code());
    }
}
