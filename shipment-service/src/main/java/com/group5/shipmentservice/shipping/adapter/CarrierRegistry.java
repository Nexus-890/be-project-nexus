package com.group5.shipmentservice.shipping.adapter;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shared.config.ShippingProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class CarrierRegistry {
    private final List<CarrierAdapter> adapters;
    private final List<String> quoteCarriers;

    //lấy ra list các adapter đã được đăng kí
    public CarrierRegistry(List<CarrierAdapter> adapters) {
        this(adapters, null);
    }

    @Autowired
    public CarrierRegistry(List<CarrierAdapter> adapters, ShippingProperties properties) {
        this.quoteCarriers = properties == null ? null : properties.offeredCarriers();
        Set<String> codes = new HashSet<>();
        for (CarrierAdapter adapter : adapters) {
            if (adapter.code() == null || adapter.code().isBlank()
                    || adapter.name() == null || adapter.name().isBlank()) {
                throw new IllegalStateException("Nhà vận chuyển phải có mã và tên");
            }
            if (!codes.add(adapter.code())) {
                throw new IllegalStateException("Mã nhà vận chuyển bị trùng: " + adapter.code());
            }
        }
        this.adapters = adapters.stream().sorted(Comparator.comparing(CarrierAdapter::code)).toList();
    }

    public List<CarrierAdapter> getAdapters() {
        return adapters;
    }

    public CarrierAdapter getDefaultAdapter() {
        return getQuoteAdapters().stream().findFirst()
                .orElseThrow(() -> new DomainException(ShipmentErrorCode.CARRIER_CONFIGURATION_ERROR));
    }

    public List<CarrierAdapter> getQuoteAdapters() {
        if (quoteCarriers == null) return adapters;
        return quoteCarriers.stream().flatMap(code -> adapters.stream().filter(adapter -> adapter.code().equals(code))).toList();
    }
}
