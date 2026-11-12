package com.group5.shipmentservice.shipping.service;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shipping.adapter.CarrierAdapter;
import com.group5.shipmentservice.shipping.adapter.CarrierRegistry;
import com.group5.shipmentservice.shipping.adapter.model.CarrierAddress;
import com.group5.shipmentservice.shipping.dto.response.LocalityResponse;
import com.group5.shipmentservice.shipping.resilience.CarrierExecutor;
import com.group5.shipmentservice.shipping.resilience.ShippingDeadline;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ShippingAddressService {
    private final CarrierRegistry carriers;
    private final CarrierExecutor executor;

    //lấy các mục như tỉnh thành mã -> tên chuẩn
    public ShippingAddressService(CarrierRegistry carriers, CarrierExecutor executor) {
        this.carriers = carriers;
        this.executor = executor;
    }

    public List<LocalityResponse> getProvinces() {
        CarrierAdapter adapter = carriers.getDefaultAdapter();
        return executor.execute(adapter.code(), adapter::getProvinces);
    }

    public List<LocalityResponse> getWards(String provinceCode) {
        validateParentCode(provinceCode);
        CarrierAdapter adapter = carriers.getDefaultAdapter();
        return executor.execute(adapter.code(), deadline -> {
            requireCode(adapter.getProvinces(deadline), provinceCode);
            return adapter.getWards(provinceCode, deadline);
        });
    }

    public CarrierAddress resolveAddress(CarrierAdapter adapter, String provinceCode, String wardCode,
                                          String addressLine, ShippingDeadline deadline) {
        LocalityResponse province = requireCode(adapter.getProvinces(deadline), provinceCode);
        LocalityResponse ward = requireCode(adapter.getWards(provinceCode, deadline), wardCode);
        return new CarrierAddress(province.name(), ward.name(), addressLine);
    }

    private void validateParentCode(String code) {
        if (code == null || !code.matches("[1-9][0-9]{0,8}")) {
            throw new DomainException(ShipmentErrorCode.INVALID_PARAMETER);
        }
    }

    private LocalityResponse requireCode(List<LocalityResponse> localities, String code) {
        return localities.stream().filter(locality -> locality.code().equals(code)).findFirst()
                .orElseThrow(() -> new DomainException(ShipmentErrorCode.INVALID_ADDRESS));
    }
}
