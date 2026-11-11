package com.group5.shipmentservice.shipping.adapter;

import com.group5.shipmentservice.shipping.adapter.model.CarrierQuoteRequest;
import com.group5.shipmentservice.shipping.dto.response.LocalityResponse;
import com.group5.shipmentservice.shipping.dto.response.ShippingQuoteResponse;
import com.group5.shipmentservice.shipping.enums.ShippingServiceType;
import com.group5.shipmentservice.shipping.resilience.ShippingDeadline;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

public class FakeCarrierAdapter implements CarrierAdapter {
    private final Clock clock;

    public FakeCarrierAdapter() {
        this(Clock.systemUTC());
    }

    public FakeCarrierAdapter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String code() {
        return "FAKE";
    }

    @Override
    public String name() {
        return "Nhà vận chuyển giả lập";
    }

    @Override
    public List<LocalityResponse> getProvinces(ShippingDeadline deadline) {
        return List.of(new LocalityResponse("1000001", "Hồ Chí Minh"), new LocalityResponse("1000002", "Hà Nội"));
    }

    @Override
    public List<LocalityResponse> getWards(String provinceCode, ShippingDeadline deadline) {
        return switch (provinceCode) {
            case "1000001" -> List.of(new LocalityResponse("1003646", "Phường Vũng Tàu"));
            case "1000002" -> List.of(new LocalityResponse("1000003", "Phường Ba Đình"));
            default -> List.of();
        };
    }

    @Override
    public List<ShippingQuoteResponse> quote(CarrierQuoteRequest request, ShippingDeadline deadline) {
        return List.of(new ShippingQuoteResponse(code(), ShippingServiceType.forWeight(request.parcel().weightGrams()),
                new BigDecimal("30000"),
                OffsetDateTime.now(clock).plusDays(3)));
    }
}
