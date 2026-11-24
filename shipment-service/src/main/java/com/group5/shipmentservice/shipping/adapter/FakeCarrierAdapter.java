package com.group5.shipmentservice.shipping.adapter;

import com.group5.shipmentservice.shipping.adapter.model.CarrierQuoteRequest;
import com.group5.shipmentservice.shipping.adapter.model.CarrierShipmentRequest;
import com.group5.shipmentservice.shipping.adapter.model.CarrierTrackingUpdate;
import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shipping.dto.response.LocalityResponse;
import com.group5.shipmentservice.shipping.dto.response.ShippingQuoteResponse;
import com.group5.shipmentservice.shipping.enums.ShippingServiceType;
import com.group5.shipmentservice.shipping.enums.ShipmentStatus;
import com.group5.shipmentservice.shipping.resilience.ShippingDeadline;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class FakeCarrierAdapter implements CarrierAdapter {
    private final Clock clock;
    private final String webhookSecret;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CarrierTrackingUpdate> tracking = new ConcurrentHashMap<>();

    public FakeCarrierAdapter() {
        this(Clock.systemUTC());
    }

    public FakeCarrierAdapter(Clock clock) {
        this(clock, null);
    }

    public FakeCarrierAdapter(Clock clock, String webhookSecret) {
        this.clock = clock;
        this.webhookSecret = webhookSecret;
    }

    @Override
    public String code() {
        return "FAKE";
    }

    @Override
    public String name() {
        return "Simulated carrier";
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

    @Override
    public String createShipment(CarrierShipmentRequest request, ShippingDeadline deadline) {
        deadline.remainingOrThrow();
        String code = "FAKE-" + request.requestId();
        tracking.computeIfAbsent(code, ignored -> update(code, ShipmentStatus.CREATED));
        return code;
    }

    @Override
    public CarrierTrackingUpdate track(String trackingCode, ShippingDeadline deadline) {
        deadline.remainingOrThrow();
        requireTrackingCode(trackingCode);
        return tracking.computeIfAbsent(trackingCode, code -> update(code, ShipmentStatus.CREATED));
    }

    @Override
    public void cancelShipment(String trackingCode, UUID requestId, ShippingDeadline deadline) {
        deadline.remainingOrThrow();
        requireTrackingCode(trackingCode);
        if (requestId == null) throw new DomainException(ShipmentErrorCode.INVALID_PARAMETER);
        tracking.compute(trackingCode, (code, existing) -> existing != null && existing.status() == ShipmentStatus.CANCELLED
                ? existing : update(code, ShipmentStatus.CANCELLED));
    }

    @Override
    public void verifyWebhook(byte[] body, String signature) {
        CarrierWebhookAuthentication.verify(webhookSecret, signature);
    }

    @Override
    public CarrierTrackingUpdate parseWebhook(byte[] body) {
        JsonNode data;
        try {
            data = mapper.readTree(body);
        } catch (RuntimeException error) {
            throw new DomainException(ShipmentErrorCode.INVALID_WEBHOOK);
        }
        if (data == null || !data.isObject()) throw new DomainException(ShipmentErrorCode.INVALID_WEBHOOK);
        String trackingCode = CarrierTrackingPayload.trackingCode(data, "OrderCode", ShipmentErrorCode.INVALID_WEBHOOK);
        String type = CarrierTrackingPayload.webhookType(data);
        OffsetDateTime changedAt = CarrierTrackingPayload.timestamp(data, "Time", ShipmentErrorCode.INVALID_WEBHOOK);
        ShipmentStatus status;
        try {
            status = ShipmentStatus.valueOf(CarrierTrackingPayload.requiredText(data, "Status", ShipmentErrorCode.INVALID_WEBHOOK));
        } catch (IllegalArgumentException error) {
            throw new DomainException(ShipmentErrorCode.INVALID_CARRIER_STATUS);
        }
        return new CarrierTrackingUpdate(trackingCode, status,
                CarrierTrackingPayload.identity(code(), trackingCode, type + ":" + status, changedAt), changedAt,
                CarrierTrackingPayload.description(data));
    }

    public void setTrackingStatus(String trackingCode, ShipmentStatus status) {
        requireTrackingCode(trackingCode);
        if (status == null) throw new DomainException(ShipmentErrorCode.INVALID_CARRIER_STATUS);
        tracking.put(trackingCode, update(trackingCode, status));
    }

    private CarrierTrackingUpdate update(String trackingCode, ShipmentStatus status) {
        OffsetDateTime changedAt = OffsetDateTime.now(clock);
        return new CarrierTrackingUpdate(trackingCode, status,
                CarrierTrackingPayload.identity(code(), trackingCode, "poll:" + status, changedAt), changedAt, status.name());
    }

    private void requireTrackingCode(String trackingCode) {
        if (trackingCode == null || trackingCode.length() > 80 || !trackingCode.matches("[A-Za-z0-9_-]+")) {
            throw new DomainException(ShipmentErrorCode.INVALID_PARAMETER);
        }
    }
}
