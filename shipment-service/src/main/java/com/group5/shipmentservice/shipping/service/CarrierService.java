package com.group5.shipmentservice.shipping.service;

import com.group5.shipmentservice.shipping.adapter.CarrierRegistry;
import com.group5.shipmentservice.shipping.dto.response.CarrierResponse;
import com.group5.shipmentservice.shipping.resilience.CarrierExecutor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CarrierService {
    private final CarrierRegistry carrierRegistry;
    private final CarrierExecutor executor;

    //lấy các adapter đã đăng kí biến thành response
    public CarrierService(CarrierRegistry carrierRegistry, CarrierExecutor executor) {
        this.carrierRegistry = carrierRegistry;
        this.executor = executor;
    }

    public List<CarrierResponse> getCarriers() {
        var adapters = carrierRegistry.getQuoteAdapters();
        String defaultCode = adapters.isEmpty() ? null : carrierRegistry.getDefaultAdapter().code();
        return adapters.stream()
                .map(adapter -> new CarrierResponse(adapter.code(), adapter.name(),
                        adapter.code().equals(defaultCode), executor.isAvailable(adapter.code())))
                .toList();
    }
}
