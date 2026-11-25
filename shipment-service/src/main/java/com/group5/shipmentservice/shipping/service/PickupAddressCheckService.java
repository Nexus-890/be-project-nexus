package com.group5.shipmentservice.shipping.service;

import com.group5.shipmentservice.shipping.adapter.CarrierAdapter;
import com.group5.shipmentservice.shipping.adapter.CarrierRegistry;
import com.group5.shipmentservice.shipping.adapter.model.CarrierAddress;
import com.group5.shipmentservice.shipping.adapter.model.CarrierQuoteRequest;
import com.group5.shipmentservice.shipping.dto.request.ParcelRequest;
import com.group5.shipmentservice.shipping.dto.request.PickupAddressRequest;
import com.group5.shipmentservice.shipping.dto.response.PickupAddressCheckResponse;
import com.group5.shipmentservice.shipping.resilience.CarrierExecutor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class PickupAddressCheckService {
    private static final ParcelRequest PROBE_PARCEL = new ParcelRequest(500, 10, 10, 10);
    private static final String PROBE_NAME = "Nexus pickup check";
    private static final String PROBE_PHONE = "0900000000";

    private final CarrierRegistry carriers;
    private final CarrierExecutor executor;
    private final ShippingAddressService addresses;

    public PickupAddressCheckService(CarrierRegistry carriers, CarrierExecutor executor,
                                     ShippingAddressService addresses) {
        this.carriers = carriers;
        this.executor = executor;
        this.addresses = addresses;
    }

    public PickupAddressCheckResponse check(PickupAddressRequest request) {
        CarrierAdapter adapter = carriers.getDefaultAdapter();
        String line = request.line1().strip();
        return executor.execute(adapter.code(), deadline -> {
            CarrierAddress pickup = addresses.resolveAddress(adapter, request.provinceCode(), request.wardCode(),
                    line, deadline);
            CarrierAddress sameArea = new CarrierAddress(pickup.provinceName(), pickup.wardName(), pickup.wardName());
            adapter.quote(new CarrierQuoteRequest(pickup, sameArea, PROBE_PARCEL, BigDecimal.ZERO,
                    PROBE_NAME, PROBE_PHONE, PROBE_NAME), deadline);
            return new PickupAddressCheckResponse(adapter.code(), request.provinceCode(), pickup.provinceName(),
                    request.wardCode(), pickup.wardName(), line);
        });
    }
}
