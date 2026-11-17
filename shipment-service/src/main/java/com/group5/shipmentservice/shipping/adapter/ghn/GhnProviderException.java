package com.group5.shipmentservice.shipping.adapter.ghn;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;

/** Provider metadata stays internal; clients receive only the stable service error. */
final class GhnProviderException extends DomainException {
    private final String providerCode;
    private final boolean orderNotFound;

    GhnProviderException(ShipmentErrorCode code, String providerCode, boolean orderNotFound) {
        super(code);
        this.providerCode = providerCode;
        this.orderNotFound = orderNotFound;
    }

    boolean isAddressConflict() { return "TO_ADDRESS_CONFLICT".equals(providerCode); }
    boolean isOrderNotFound() { return orderNotFound; }
}
