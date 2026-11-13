package com.group5.shipmentservice.shipping.adapter;

import com.group5.shipmentservice.shipping.adapter.model.CarrierQuoteRequest;
import com.group5.shipmentservice.shipping.adapter.model.CarrierShipmentRequest;
import com.group5.shipmentservice.shipping.adapter.model.CarrierTrackingUpdate;
import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shipping.dto.response.LocalityResponse;
import com.group5.shipmentservice.shipping.dto.response.ShippingQuoteResponse;
import com.group5.shipmentservice.shipping.resilience.ShippingDeadline;
import java.util.List;
import java.util.UUID;

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

    default CarrierTrackingUpdate track(String trackingCode, ShippingDeadline deadline) {
        throw new DomainException(ShipmentErrorCode.CARRIER_CONFIGURATION_ERROR);
    }

    default void cancelShipment(String trackingCode, UUID requestId, ShippingDeadline deadline) {
        throw new DomainException(ShipmentErrorCode.CARRIER_CONFIGURATION_ERROR);
    }

    default CarrierTrackingUpdate parseWebhook(byte[] body) {
        throw new DomainException(ShipmentErrorCode.INVALID_WEBHOOK);
    }

    default void verifyWebhook(byte[] body, String signature) {
        throw new DomainException(ShipmentErrorCode.INVALID_SIGNATURE);
    }
}
