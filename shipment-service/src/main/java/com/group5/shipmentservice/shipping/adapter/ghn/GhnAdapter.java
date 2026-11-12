package com.group5.shipmentservice.shipping.adapter.ghn;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.config.GhnProperties;
import com.group5.shipmentservice.shared.config.ShippingProperties;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shipping.adapter.CarrierAdapter;
import com.group5.shipmentservice.shipping.adapter.model.CarrierAddress;
import com.group5.shipmentservice.shipping.adapter.model.CarrierQuoteRequest;
import com.group5.shipmentservice.shipping.adapter.ghn.dto.request.GhnParcelItemRequest;
import com.group5.shipmentservice.shipping.adapter.ghn.dto.request.GhnPreviewRequest;
import com.group5.shipmentservice.shipping.dto.response.LocalityResponse;
import com.group5.shipmentservice.shipping.dto.response.ShippingQuoteResponse;
import com.group5.shipmentservice.shipping.enums.ShippingServiceType;
import com.group5.shipmentservice.shipping.resilience.ShippingDeadline;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class GhnAdapter implements CarrierAdapter {
    private static final String MASTER_DATA = "/shiip/public-api/v3/master-data/";
    private static final String PREVIEW_ORDER = "/shiip/public-api/v2/shipping-order/preview";

    private final GhnHttpClient client;
    private final GhnProperties properties;
    private final Map<String, CachedLocalities> masterData = new ConcurrentHashMap<>();

    public GhnAdapter(GhnProperties properties, ShippingProperties shipping,
                      Environment environment, ObjectMapper mapper) {
        String token = environment.getProperty("GHN_TOKEN");
        if (token == null || token.isBlank() || properties.shopId() == null || properties.shopId() <= 0
                || properties.baseUrl() == null || properties.baseUrl().getHost() == null
                || !List.of("https", "http").contains(properties.baseUrl().getScheme())) {
            throw new IllegalStateException("Cần cấu hình GHN_TOKEN, GHN_SHOP_ID và địa chỉ API GHN hợp lệ");
        }
        this.properties = properties;
        this.client = new GhnHttpClient(properties, shipping, token, mapper);
    }

    @Override
    public String code() {
        return "GHN";
    }

    @Override
    public String name() {
        return "Giao Hàng Nhanh";
    }

    @Override
    public List<LocalityResponse> getProvinces(ShippingDeadline deadline) {
        return cachedLocalities("provinces", deadline, () -> {
            JsonNode data = client.get(MASTER_DATA + "province/all?offset=0&limit=200", deadline);
            return localities(data, "province", 1);
        });
    }

    @Override
    public List<LocalityResponse> getWards(String provinceCode, ShippingDeadline deadline) {
        int province = numericCode(provinceCode);
        return cachedLocalities("wards:" + province, deadline, () -> {
            JsonNode data = client.get(MASTER_DATA + "ward/all-by-province-id?province_id=" + province
                    + "&offset=0&limit=200", deadline);
            return localities(data, "ward", province);
        });
    }

    private List<LocalityResponse> cachedLocalities(String key, ShippingDeadline deadline,
                                                    Supplier<List<LocalityResponse>> loader) {
        deadline.remainingOrThrow();
        CachedLocalities cached = masterData.get(key);
        if (cached != null && cached.expiresAt() - System.nanoTime() > 0) {
            return cached.values();
        }
        List<LocalityResponse> values = List.copyOf(loader.get());
        deadline.remainingOrThrow();
        if (properties.masterDataCacheTtlMs() > 0) {
            long ttlNanos = Duration.ofMillis(properties.masterDataCacheTtlMs()).toNanos();
            masterData.put(key, new CachedLocalities(System.nanoTime() + ttlNanos, values));
        }
        return values;
    }

    @Override
    public List<ShippingQuoteResponse> quote(CarrierQuoteRequest request, ShippingDeadline deadline) {
        ShippingServiceType serviceType = ShippingServiceType.forWeight(request.parcel().weightGrams());
        int ghnServiceType = serviceType == ShippingServiceType.STANDARD ? 2 : 5;
        JsonNode data = client.post(PREVIEW_ORDER, previewBody(request, ghnServiceType), deadline);
        JsonNode orderCode = data.path("order_code");
        JsonNode fee = data.path("total_fee");
        if (!data.isObject() || !orderCode.isString() || !orderCode.asString().isEmpty()
                || !fee.isNumber() || fee.decimalValue().signum() < 0) {
            throw invalidResponse();
        }
        return List.of(new ShippingQuoteResponse(code(), serviceType,
                fee.decimalValue(), estimatedAt(data.path("expected_delivery_time"))));
    }

    private GhnPreviewRequest previewBody(CarrierQuoteRequest request, int serviceType) {
        BigDecimal insurance = request.insuranceValue() == null ? BigDecimal.ZERO : request.insuranceValue();
        if (insurance.signum() < 0 || insurance.compareTo(BigDecimal.valueOf(5_000_000)) > 0
                || insurance.stripTrailingZeros().scale() > 0) {
            throw new DomainException(ShipmentErrorCode.INVALID_REQUEST);
        }
        List<GhnParcelItemRequest> items = serviceType == 5
                ? List.of(new GhnParcelItemRequest(request.content(), 1, request.parcel().weightGrams(),
                        request.parcel().lengthCm(), request.parcel().widthCm(), request.parcel().heightCm()))
                : null;
        return GhnPreviewRequest.builder()
                .newFromAddress(true) //true để lấy theo danh sách new
                .fromProvinceName(request.pickup().provinceName())
                .fromWardName(request.pickup().wardName())
                .fromAddress(fullAddress(request.pickup()))
                .newToAddress(true)
                .toProvinceName(request.toAddress().provinceName())
                .toWardName(request.toAddress().wardName())
                .toAddress(fullAddress(request.toAddress()))
                .toName(request.recipientName())
                .toPhone(request.recipientPhone())
                .paymentTypeId(properties.paymentTypeId())
                .requiredNote(properties.requiredNote().name())
                .content(request.content())
                .serviceTypeId(serviceType)
                .weight(request.parcel().weightGrams())
                .length(request.parcel().lengthCm())
                .width(request.parcel().widthCm())
                .height(request.parcel().heightCm())
                .insuranceValue(insurance.intValueExact())
                .codAmount(0)
                .items(items)
                .build();
    }

    private String fullAddress(CarrierAddress address) {
        return String.join(", ", address.addressLine(), address.wardName(), address.provinceName());
    }

    private List<LocalityResponse> localities(JsonNode data, String expectedType, int expectedParent) {
        if (!data.isArray()) {
            throw invalidResponse();
        }
        Map<String, LocalityResponse> results = new LinkedHashMap<>();
        for (JsonNode item : data) {
            JsonNode id = item.path("_id");
            JsonNode name = item.path("name");
            JsonNode type = item.path("type");
            JsonNode parent = item.path("parent_id");
            JsonNode status = item.path("status");
            if (!positiveId(id) || !name.isString() || name.asString().isBlank()
                    || !type.isString() || !expectedType.equals(type.asString())
                    || !positiveId(parent) || !positiveId(status)
                    || !List.of(1, 2, 10).contains(status.intValue())) {
                throw invalidResponse();
            }
            if (parent.intValue() != expectedParent || status.intValue() != 1) {
                continue;
            }
            LocalityResponse locality = new LocalityResponse(Integer.toString(id.intValue()), name.asString());
            if (results.putIfAbsent(locality.code(), locality) != null) {
                throw invalidResponse();
            }
        }
        return results.values().stream().sorted(Comparator.comparing(LocalityResponse::code)).toList();
    }

    private boolean positiveId(JsonNode value) {
        return value.isIntegralNumber() && value.bigIntegerValue().signum() > 0
                && value.bigIntegerValue().compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0;
    }

    private OffsetDateTime estimatedAt(JsonNode estimate) {
        if (!estimate.isString() || estimate.asString().isBlank()) {
            throw invalidResponse();
        }
        try {
            return OffsetDateTime.parse(estimate.asString());
        } catch (DateTimeException error) {
            throw invalidResponse();
        }
    }

    private int numericCode(String code) {
        try {
            int value = Integer.parseInt(code);
            if (value <= 0 || !Integer.toString(value).equals(code)) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException error) {
            throw new DomainException(ShipmentErrorCode.INVALID_ADDRESS);
        }
    }

    private DomainException invalidResponse() {
        return new DomainException(ShipmentErrorCode.INVALID_CARRIER_RESPONSE);
    }

    private record CachedLocalities(long expiresAt, List<LocalityResponse> values) {
    }
}
